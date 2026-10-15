package pl.kacosmetology.scheduler.user

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.user.dto.CustomerStatusResponse

/** Business logic for owner-scoped manual customer block/unblock operations and customer notes. */
@Service
class CustomerService(
    private val userRepository: UserRepository,
    private val reservationRepository: ReservationRepository,
    private val companyCustomerBlockRepository: CompanyCustomerBlockRepository,
    private val companyCustomerRepository: CompanyCustomerRepository,
) {

    /**
     * Returns all customers who have at least one reservation at the given company, enriched with their company-scoped
     * block status and notes. Sorted by last name, then first name.
     */
    @Transactional(readOnly = true)
    fun listCustomers(companyId: Long): List<CustomerStatusResponse> {
        val profiles = companyCustomerRepository.findByCompanyId(companyId)
        val accounts = userRepository.findAllById(profiles.map { it.userId }).associateBy { it.id }
        val blocks = companyCustomerBlockRepository.findByCompanyId(companyId).associateBy { it.customerId }
        return profiles
            .mapNotNull { profile ->
                val account = accounts[profile.userId] ?: return@mapNotNull null
                val block = blocks[profile.userId]
                CustomerStatusResponse(
                    profile.userId,
                    profile.firstName,
                    profile.lastName,
                    account.phoneNumber,
                    block?.noShowCount ?: 0,
                    block?.blocked ?: false,
                    profile.notes,
                    profile.contactEmail,
                )
            }
            .sortedWith(compareBy({ it.lastName }, { it.firstName }))
    }

    /**
     * Returns the company-scoped block/no-show status for a customer visible to staff. Throws [NoSuchElementException]
     * if the user is not found.
     */
    @Transactional(readOnly = true)
    fun getCustomerStatus(customerId: Long, companyId: Long): CustomerStatusResponse {
        val companyCustomer =
            companyCustomerRepository.findByCompanyIdAndUserId(companyId, customerId)
                ?: throw NoSuchElementException("Klient nie istnieje")
        val customer = userRepository.findById(customerId).orElseThrow { NoSuchElementException("Klient nie istnieje") }
        val block = companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId)
        return CustomerStatusResponse(
            id = requireNotNull(customer.id) { "Persisted customer must have an ID" },
            firstName = companyCustomer.firstName,
            lastName = companyCustomer.lastName,
            phoneNumber = customer.phoneNumber,
            noShowCount = block?.noShowCount ?: 0,
            blocked = block?.blocked ?: false,
            notes = companyCustomer.notes,
            contactEmail = companyCustomer.contactEmail,
        )
    }

    /**
     * Updates a free-text note for an existing local customer at the given company. Blank notes are normalised to null
     * (cleared). Throws [NoSuchElementException] if the user is not found.
     */
    @Transactional
    fun setCustomerNotes(customerId: Long, companyId: Long, notes: String?) {
        val record =
            companyCustomerRepository.findByCompanyIdAndUserId(companyId, customerId)
                ?: throw NoSuchElementException("Klient nie istnieje")
        record.notes = notes?.ifBlank { null }
        companyCustomerRepository.save(record)
    }

    /** Reads the company's visit history for either staff role, after checking the local relationship. */
    @Transactional(readOnly = true)
    fun getVisitHistory(
        customerId: Long,
        companyId: Long,
    ): List<pl.kacosmetology.scheduler.user.dto.CustomerVisitResponse> {
        companyCustomerRepository.findByCompanyIdAndUserId(companyId, customerId)
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
            )
        }
    }

    /** Updates only an existing company profile; global phone identities remain unchanged. */
    @Transactional
    fun updateProfile(
        customerId: Long,
        companyId: Long,
        request: pl.kacosmetology.scheduler.user.dto.UpdateCustomerProfileRequest,
    ) {
        val profile =
            companyCustomerRepository.findByCompanyIdAndUserId(companyId, customerId)
                ?: throw NoSuchElementException("Klient nie istnieje")
        profile.firstName = request.firstName.trim()
        profile.lastName = request.lastName.trim()
        profile.contactEmail = request.contactEmail?.trim()?.ifBlank { null }
    }

    /**
     * Blocks a customer from online booking at the given company. Throws [NoSuchElementException] if the user is not
     * found. Throws [IllegalArgumentException] if the customer has no reservations in the given company (prevents
     * blocking arbitrary users from another company).
     */
    @Transactional
    fun blockCustomer(customerId: Long, companyId: Long) {
        if (!userRepository.existsById(customerId)) {
            throw NoSuchElementException("Klient nie istnieje")
        }
        if (!reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId)) {
            throw IllegalArgumentException("Klient nie ma żadnych rezerwacji w tej firmie")
        }
        val block =
            companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId)
                ?: CompanyCustomerBlock(companyId = companyId, customerId = customerId)
        block.blocked = true
        companyCustomerBlockRepository.save(block)
    }

    /**
     * Unblocks a customer and resets their no-show counter to zero for the given company. Throws
     * [NoSuchElementException] if the user is not found. Throws [IllegalArgumentException] if the customer has no
     * reservations in the given company.
     */
    @Transactional
    fun unblockCustomer(customerId: Long, companyId: Long) {
        if (!userRepository.existsById(customerId)) {
            throw NoSuchElementException("Klient nie istnieje")
        }
        if (!reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId)) {
            throw IllegalArgumentException("Klient nie ma żadnych rezerwacji w tej firmie")
        }
        val block = companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) ?: return
        block.blocked = false
        block.noShowCount = 0
        companyCustomerBlockRepository.save(block)
    }
}
