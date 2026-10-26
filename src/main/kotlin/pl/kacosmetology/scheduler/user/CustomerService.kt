package pl.kacosmetology.scheduler.user

import java.util.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.user.dto.CustomerStatusResponse

/** Business logic for owner-scoped manual customer block/unblock operations and customer notes. */
@Service
class CustomerService(
    private val companyCustomerBlockRepository: CompanyCustomerBlockRepository,
    private val companyCustomerRepository: CompanyCustomerRepository,
    private val blocking: CustomerBlockingPolicy,
) {

    /**
     * Returns all customers who have at least one reservation at the given company, enriched with their company-scoped
     * block status and notes. Sorted by last name, then first name.
     */
    @Transactional(readOnly = true)
    fun listCustomers(companyId: UUID): List<CustomerStatusResponse> {
        val profiles = companyCustomerRepository.findByCompanyIdAndFirstBookingAtIsNotNull(companyId)
        val blocks = companyCustomerBlockRepository.findByCompanyId(companyId).associateBy { it.customerId }
        return profiles
            .map { profile ->
                val block = blocks[requireNotNull(profile.id)]
                CustomerStatusResponse(
                    requireNotNull(profile.id),
                    requireNotNull(profile.firstName),
                    requireNotNull(profile.lastName),
                    profile.phoneNumber,
                    blocking.currentCount(companyId, requireNotNull(profile.id)),
                    block?.blocked ?: false,
                    profile.notes,
                    profile.contactEmail,
                    profile.version,
                )
            }
            .sortedWith(compareBy({ it.lastName }, { it.firstName }))
    }

    /**
     * Returns the company-scoped block/no-show status for a customer visible to staff. Throws [NoSuchElementException]
     * if the user is not found.
     */
    @Transactional(readOnly = true)
    fun getCustomerStatus(customerId: UUID, companyId: UUID): CustomerStatusResponse {
        val companyCustomer =
            companyCustomerRepository.findByCompanyIdAndIdAndFirstBookingAtIsNotNull(companyId, customerId)
                ?: throw NoSuchElementException("Klient nie istnieje")
        val block = companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId)
        return CustomerStatusResponse(
            id = requireNotNull(companyCustomer.id) { "Persisted customer must have an ID" },
            firstName = requireNotNull(companyCustomer.firstName),
            lastName = requireNotNull(companyCustomer.lastName),
            phoneNumber = companyCustomer.phoneNumber,
            noShowCount = blocking.currentCount(companyId, customerId),
            blocked = block?.blocked ?: false,
            notes = companyCustomer.notes,
            contactEmail = companyCustomer.contactEmail,
            revision = companyCustomer.version,
        )
    }

    /**
     * Updates a free-text note for an existing local customer at the given company. Blank notes are normalised to null
     * (cleared). Throws [NoSuchElementException] if the user is not found.
     */
    @Transactional
    fun setCustomerNotes(customerId: UUID, companyId: UUID, notes: String?) {
        val record =
            companyCustomerRepository.findByCompanyIdAndIdAndFirstBookingAtIsNotNull(companyId, customerId)
                ?: throw NoSuchElementException("Klient nie istnieje")
        record.notes = notes?.ifBlank { null }
        companyCustomerRepository.save(record)
    }

    /** Reads the company's visit history for either staff role, after checking the local relationship. */
    @Transactional(readOnly = true)
    fun getVisitHistory(
        customerId: UUID,
        companyId: UUID,
    ): List<pl.kacosmetology.scheduler.user.dto.CustomerVisitResponse> {
        companyCustomerRepository.findByCompanyIdAndIdAndFirstBookingAtIsNotNull(companyId, customerId)
            ?: throw NoSuchElementException("Klient nie istnieje")
        return companyCustomerRepository.findVisitHistory(companyId, customerId).map {
            pl.kacosmetology.scheduler.user.dto.CustomerVisitResponse(
                requireNotNull(it.id),
                it.employeeId,
                it.serviceId,
                it.acceptedServiceName,
                it.price,
                it.startTime,
                it.endTime,
                it.status,
                it.createdAt,
                it.internalNotes,
            )
        }
    }

    /** Updates only an existing company profile; the local phone authentication identity remains unchanged. */
    @Transactional
    fun updateProfile(
        customerId: UUID,
        companyId: UUID,
        request: pl.kacosmetology.scheduler.user.dto.UpdateCustomerProfileRequest,
        revision: Long,
    ) {
        val profile =
            companyCustomerRepository.findByCompanyIdAndIdAndFirstBookingAtIsNotNull(companyId, customerId)
                ?: throw NoSuchElementException("Klient nie istnieje")
        if (profile.version != revision)
            throw pl.kacosmetology.scheduler.config.ApiException(
                pl.kacosmetology.scheduler.config.ErrorCode.CONCURRENT_MODIFICATION,
                "Profil klienta został zmieniony. Odśwież dane",
            )
        profile.firstName = request.firstName.trim()
        profile.lastName = request.lastName.trim()
        profile.contactEmail = request.contactEmail?.trim()?.ifBlank { null }
    }

    /** Manually blocks only a legitimate local customer and cancels future visits atomically. */
    @Transactional fun blockCustomer(customerId: UUID, companyId: UUID) = blocking.block(companyId, customerId)

    /** Gives a company-local reprieve without resetting penalties or restoring cancelled appointments. */
    @Transactional fun unblockCustomer(customerId: UUID, companyId: UUID) = blocking.unblock(companyId, customerId)
}
