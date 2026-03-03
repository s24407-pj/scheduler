package pl.kacosmetology.scheduler.user

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import java.util.*

@ExtendWith(MockKExtension::class)
class CustomerServiceTest {

    @MockK
    private lateinit var userRepository: UserRepository

    @MockK
    private lateinit var reservationRepository: ReservationRepository

    @InjectMockKs
    private lateinit var customerService: CustomerService

    private val companyId = 1L
    private val customerId = 100L

    @Test
    fun `blockCustomer should set blocked to true`() {
        // GIVEN
        val customer = User(id = customerId, phoneNumber = "+48111000111", firstName = "Jan", lastName = "Kowalski")

        every { userRepository.findById(customerId) } returns Optional.of(customer)
        every { reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId) } returns true
        every { userRepository.save(any()) } answers { firstArg() }

        // WHEN
        customerService.blockCustomer(customerId, companyId)

        // THEN
        assert(customer.blocked)
        verify(exactly = 1) { userRepository.save(customer) }
    }

    @Test
    fun `unblockCustomer should set blocked to false and reset noShowCount`() {
        // GIVEN
        val customer = User(id = customerId, phoneNumber = "+48111000111", firstName = "Jan", lastName = "Kowalski", blocked = true, noShowCount = 5)

        every { userRepository.findById(customerId) } returns Optional.of(customer)
        every { reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId) } returns true
        every { userRepository.save(any()) } answers { firstArg() }

        // WHEN
        customerService.unblockCustomer(customerId, companyId)

        // THEN
        assertFalse(customer.blocked)
        assertEquals(0, customer.noShowCount)
        verify(exactly = 1) { userRepository.save(customer) }
    }

    @Test
    fun `blockCustomer should throw when customer not found`() {
        // GIVEN
        every { userRepository.findById(customerId) } returns Optional.empty()

        // WHEN & THEN
        assertThrows<NoSuchElementException> {
            customerService.blockCustomer(customerId, companyId)
        }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `blockCustomer should throw when customer has no reservations in company`() {
        // GIVEN
        val customer = User(id = customerId, phoneNumber = "+48111000111", firstName = "Jan", lastName = "Kowalski")

        every { userRepository.findById(customerId) } returns Optional.of(customer)
        every { reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId) } returns false

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            customerService.blockCustomer(customerId, companyId)
        }
        assertEquals("Klient nie ma żadnych rezerwacji w tej firmie", exception.message)
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `unblockCustomer should throw when customer has no reservations in company`() {
        // GIVEN
        val customer = User(id = customerId, phoneNumber = "+48111000111", firstName = "Jan", lastName = "Kowalski", blocked = true)

        every { userRepository.findById(customerId) } returns Optional.of(customer)
        every { reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId) } returns false

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            customerService.unblockCustomer(customerId, companyId)
        }
        assertEquals("Klient nie ma żadnych rezerwacji w tej firmie", exception.message)
        verify(exactly = 0) { userRepository.save(any()) }
    }
}
