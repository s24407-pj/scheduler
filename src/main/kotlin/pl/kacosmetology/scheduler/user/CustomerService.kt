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
    private val companyCustomerRepository: CompanyCustomerRepository
) {

    /**
     * Returns all customers who have at least one reservation at the given company,
     * enriched with their company-scoped block status and notes. Sorted by last name, then first name.
     */
    @Transactional(readOnly = true)
    fun listCustomers(companyId: Long): List<CustomerStatusResponse> {
        val customerIds = reservationRepository.findDistinctCustomerIdsByCompanyId(companyId)
        if (customerIds.isEmpty()) return emptyList()
        val usersById = userRepository.findAllById(customerIds).associateBy { it.id }
        val blocksByCustomerId = companyCustomerBlockRepository.findByCompanyId(companyId)
            .associateBy { it.customerId }
        val companyCustomersByUserId = companyCustomerRepository.findByCompanyId(companyId)
            .associateBy { it.userId }
        return customerIds
            .mapNotNull { id ->
                val user = usersById[id] ?: return@mapNotNull null
                val block = blocksByCustomerId[id]
                val companyCustomer = companyCustomersByUserId[id]
                CustomerStatusResponse(
                    id = requireNotNull(user.id) { "Persisted customer must have an ID" },
                    firstName = user.firstName,
                    lastName = user.lastName,
                    phoneNumber = user.phoneNumber,
                    noShowCount = block?.noShowCount ?: 0,
                    blocked = block?.blocked ?: false,
                    notes = companyCustomer?.notes
                )
            }
            .sortedWith(compareBy({ it.lastName }, { it.firstName }))
    }

    /**
     * Returns the company-scoped block/no-show status for a customer visible to staff.
     * Throws [NoSuchElementException] if the user is not found.
     */
    @Transactional(readOnly = true)
    fun getCustomerStatus(customerId: Long, companyId: Long): CustomerStatusResponse {
        val customer = userRepository.findById(customerId)
            .orElseThrow { NoSuchElementException("Klient nie istnieje") }
        val block = companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId)
        val companyCustomer = companyCustomerRepository.findByCompanyIdAndUserId(companyId, customerId)
        return CustomerStatusResponse(
            id = requireNotNull(customer.id) { "Persisted customer must have an ID" },
            firstName = customer.firstName,
            lastName = customer.lastName,
            phoneNumber = customer.phoneNumber,
            noShowCount = block?.noShowCount ?: 0,
            blocked = block?.blocked ?: false,
            notes = companyCustomer?.notes
        )
    }

    /**
     * Creates or updates a free-text note for a customer at the given company.
     * Blank notes are normalised to null (cleared).
     * Throws [NoSuchElementException] if the user is not found.
     */
    @Transactional
    fun setCustomerNotes(customerId: Long, companyId: Long, notes: String?) {
        if (!userRepository.existsById(customerId)) {
            throw NoSuchElementException("Klient nie istnieje")
        }
        val record = companyCustomerRepository.findByCompanyIdAndUserId(companyId, customerId)
            ?: CompanyCustomer(companyId = companyId, userId = customerId)
        record.notes = notes?.ifBlank { null }
        companyCustomerRepository.save(record)
    }

    /**
     * Blocks a customer from online booking at the given company.
     * Throws [NoSuchElementException] if the user is not found.
     * Throws [IllegalArgumentException] if the customer has no reservations in the given company
     * (prevents blocking arbitrary users from another company).
     */
    @Transactional
    fun blockCustomer(customerId: Long, companyId: Long) {
        if (!userRepository.existsById(customerId)) {
            throw NoSuchElementException("Klient nie istnieje")
        }
        if (!reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId)) {
            throw IllegalArgumentException("Klient nie ma żadnych rezerwacji w tej firmie")
        }
        val block = companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId)
            ?: CompanyCustomerBlock(companyId = companyId, customerId = customerId)
        block.blocked = true
        companyCustomerBlockRepository.save(block)
    }

    /**
     * Unblocks a customer and resets their no-show counter to zero for the given company.
     * Throws [NoSuchElementException] if the user is not found.
     * Throws [IllegalArgumentException] if the customer has no reservations in the given company.
     */
    @Transactional
    fun unblockCustomer(customerId: Long, companyId: Long) {
        if (!userRepository.existsById(customerId)) {
            throw NoSuchElementException("Klient nie istnieje")
        }
        if (!reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId)) {
            throw IllegalArgumentException("Klient nie ma żadnych rezerwacji w tej firmie")
        }
        val block = companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId)
            ?: return
        block.blocked = false
        block.noShowCount = 0
        companyCustomerBlockRepository.save(block)
    }
}
