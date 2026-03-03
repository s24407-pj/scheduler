package pl.kacosmetology.scheduler.user

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.user.dto.CustomerStatusResponse

/** Business logic for owner-scoped manual customer block/unblock operations. */
@Service
class CustomerService(
    private val userRepository: UserRepository,
    private val reservationRepository: ReservationRepository
) {

    /**
     * Returns the block/no-show status for a customer visible to staff.
     * Throws [NoSuchElementException] if the user is not found.
     */
    @Transactional(readOnly = true)
    fun getCustomerStatus(customerId: Long): CustomerStatusResponse {
        val customer = userRepository.findById(customerId)
            .orElseThrow { NoSuchElementException("Klient nie istnieje") }
        return CustomerStatusResponse(
            id = customer.id,
            firstName = customer.firstName,
            lastName = customer.lastName,
            noShowCount = customer.noShowCount,
            blocked = customer.blocked
        )
    }

    /**
     * Blocks a customer from online booking.
     * Throws [NoSuchElementException] if the user is not found.
     * Throws [IllegalArgumentException] if the customer has no reservations in the given company
     * (prevents blocking arbitrary users from another company).
     */
    @Transactional
    fun blockCustomer(customerId: Long, companyId: Long) {
        val customer = userRepository.findById(customerId)
            .orElseThrow { NoSuchElementException("Klient nie istnieje") }
        if (!reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId)) {
            throw IllegalArgumentException("Klient nie ma żadnych rezerwacji w tej firmie")
        }
        customer.blocked = true
        userRepository.save(customer)
    }

    /**
     * Unblocks a customer and resets their no-show counter to zero.
     * Throws [NoSuchElementException] if the user is not found.
     * Throws [IllegalArgumentException] if the customer has no reservations in the given company.
     */
    @Transactional
    fun unblockCustomer(customerId: Long, companyId: Long) {
        val customer = userRepository.findById(customerId)
            .orElseThrow { NoSuchElementException("Klient nie istnieje") }
        if (!reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId)) {
            throw IllegalArgumentException("Klient nie ma żadnych rezerwacji w tej firmie")
        }
        customer.blocked = false
        customer.noShowCount = 0
        userRepository.save(customer)
    }
}
