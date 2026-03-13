package pl.kacosmetology.scheduler.user

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
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

    @MockK
    private lateinit var companyCustomerBlockRepository: CompanyCustomerBlockRepository

    @MockK
    private lateinit var companyCustomerRepository: CompanyCustomerRepository

    @InjectMockKs
    private lateinit var customerService: CustomerService

    private val companyId = 1L
    private val customerId = 100L

    @Test
    fun `listCustomers returns customers with phone number sorted by last name`() {
        // GIVEN
        val alice = User(id = 1L, phoneNumber = "+48100000001", firstName = "Alice", lastName = "Zielińska")
        val bob = User(id = 2L, phoneNumber = "+48100000002", firstName = "Bob", lastName = "Adamski")
        every { reservationRepository.findDistinctCustomerIdsByCompanyId(companyId) } returns listOf(1L, 2L)
        every { userRepository.findAllById(listOf(1L, 2L)) } returns listOf(alice, bob)
        every { companyCustomerBlockRepository.findByCompanyId(companyId) } returns emptyList()
        every { companyCustomerRepository.findByCompanyId(companyId) } returns emptyList()

        // WHEN
        val result = customerService.listCustomers(companyId)

        // THEN
        assertEquals(2, result.size)
        assertEquals("Adamski", result[0].lastName)
        assertEquals("Zielińska", result[1].lastName)
        assertEquals("+48100000001", result.first { it.firstName == "Alice" }.phoneNumber)
    }

    @Test
    fun `listCustomers returns empty list when no reservations`() {
        // GIVEN
        every { reservationRepository.findDistinctCustomerIdsByCompanyId(companyId) } returns emptyList()

        // WHEN
        val result = customerService.listCustomers(companyId)

        // THEN
        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `listCustomers includes block status and notes`() {
        // GIVEN
        val customer = User(id = customerId, phoneNumber = "+48111111111", firstName = "Jan", lastName = "Kowalski")
        val block = CompanyCustomerBlock(companyId = companyId, customerId = customerId, noShowCount = 3, blocked = true)
        val companyCustomer = CompanyCustomer(companyId = companyId, userId = customerId, notes = "VIP")
        every { reservationRepository.findDistinctCustomerIdsByCompanyId(companyId) } returns listOf(customerId)
        every { userRepository.findAllById(listOf(customerId)) } returns listOf(customer)
        every { companyCustomerBlockRepository.findByCompanyId(companyId) } returns listOf(block)
        every { companyCustomerRepository.findByCompanyId(companyId) } returns listOf(companyCustomer)

        // WHEN
        val result = customerService.listCustomers(companyId)

        // THEN
        assertEquals(1, result.size)
        assertEquals(true, result[0].blocked)
        assertEquals(3, result[0].noShowCount)
        assertEquals("VIP", result[0].notes)
    }

    @Test
    fun `blockCustomer should set blocked to true`() {
        // GIVEN
        every { userRepository.existsById(customerId) } returns true
        every { reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId) } returns true
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns null
        every { companyCustomerBlockRepository.save(any()) } answers { firstArg() }

        // WHEN
        customerService.blockCustomer(customerId, companyId)

        // THEN
        verify(exactly = 1) { companyCustomerBlockRepository.save(match { it.blocked }) }
    }

    @Test
    fun `blockCustomer should update existing block record`() {
        // GIVEN
        val existingBlock = CompanyCustomerBlock(companyId = companyId, customerId = customerId, blocked = false)
        every { userRepository.existsById(customerId) } returns true
        every { reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId) } returns true
        every {
            companyCustomerBlockRepository.findByCompanyIdAndCustomerId(
                companyId,
                customerId
            )
        } returns existingBlock
        every { companyCustomerBlockRepository.save(any()) } answers { firstArg() }

        // WHEN
        customerService.blockCustomer(customerId, companyId)

        // THEN
        assert(existingBlock.blocked)
        verify(exactly = 1) { companyCustomerBlockRepository.save(existingBlock) }
    }

    @Test
    fun `unblockCustomer should set blocked to false and reset noShowCount`() {
        // GIVEN
        val block =
            CompanyCustomerBlock(companyId = companyId, customerId = customerId, noShowCount = 5, blocked = true)
        every { userRepository.existsById(customerId) } returns true
        every { reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId) } returns true
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns block
        every { companyCustomerBlockRepository.save(any()) } answers { firstArg() }

        // WHEN
        customerService.unblockCustomer(customerId, companyId)

        // THEN
        assertFalse(block.blocked)
        assertEquals(0, block.noShowCount)
        verify(exactly = 1) { companyCustomerBlockRepository.save(block) }
    }

    @Test
    fun `unblockCustomer should do nothing when no block record exists`() {
        // GIVEN
        every { userRepository.existsById(customerId) } returns true
        every { reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId) } returns true
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns null

        // WHEN
        customerService.unblockCustomer(customerId, companyId)

        // THEN
        verify(exactly = 0) { companyCustomerBlockRepository.save(any()) }
    }

    @Test
    fun `blockCustomer should throw when customer not found`() {
        // GIVEN
        every { userRepository.existsById(customerId) } returns false

        // WHEN & THEN
        assertThrows<NoSuchElementException> {
            customerService.blockCustomer(customerId, companyId)
        }
        verify(exactly = 0) { companyCustomerBlockRepository.save(any()) }
    }

    @Test
    fun `blockCustomer should throw when customer has no reservations in company`() {
        // GIVEN
        every { userRepository.existsById(customerId) } returns true
        every { reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId) } returns false

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            customerService.blockCustomer(customerId, companyId)
        }
        assertEquals("Klient nie ma żadnych rezerwacji w tej firmie", exception.message)
        verify(exactly = 0) { companyCustomerBlockRepository.save(any()) }
    }

    @Test
    fun `unblockCustomer should throw when customer has no reservations in company`() {
        // GIVEN
        every { userRepository.existsById(customerId) } returns true
        every { reservationRepository.existsByCustomerIdAndCompanyId(customerId, companyId) } returns false

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            customerService.unblockCustomer(customerId, companyId)
        }
        assertEquals("Klient nie ma żadnych rezerwacji w tej firmie", exception.message)
        verify(exactly = 0) { companyCustomerBlockRepository.save(any()) }
    }

    @Test
    fun `getCustomerStatus includes notes from CompanyCustomer record`() {
        // GIVEN
        val customer = User(phoneNumber = "+48123456789", firstName = "Jan", lastName = "Kowalski")
        every { userRepository.findById(customerId) } returns Optional.of(customer)
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns null
        every { companyCustomerRepository.findByCompanyIdAndUserId(companyId, customerId) } returns
                CompanyCustomer(companyId = companyId, userId = customerId, notes = "VIP klient")

        // WHEN
        val result = customerService.getCustomerStatus(customerId, companyId)

        // THEN
        assertEquals("VIP klient", result.notes)
    }

    @Test
    fun `getCustomerStatus returns null notes when no CompanyCustomer record`() {
        // GIVEN
        val customer = User(phoneNumber = "+48123456789", firstName = "Jan", lastName = "Kowalski")
        every { userRepository.findById(customerId) } returns Optional.of(customer)
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns null
        every { companyCustomerRepository.findByCompanyIdAndUserId(companyId, customerId) } returns null

        // WHEN
        val result = customerService.getCustomerStatus(customerId, companyId)

        // THEN
        assertNull(result.notes)
    }

    @Test
    fun `setCustomerNotes creates new record when none exists`() {
        // GIVEN
        every { userRepository.existsById(customerId) } returns true
        every { companyCustomerRepository.findByCompanyIdAndUserId(companyId, customerId) } returns null
        every { companyCustomerRepository.save(any()) } answers { firstArg() }

        // WHEN
        customerService.setCustomerNotes(customerId, companyId, "Ważny klient")

        // THEN
        verify(exactly = 1) {
            companyCustomerRepository.save(match { it.notes == "Ważny klient" && it.companyId == companyId && it.userId == customerId })
        }
    }

    @Test
    fun `setCustomerNotes updates existing record`() {
        // GIVEN
        val existing = CompanyCustomer(companyId = companyId, userId = customerId, notes = "Stara notatka")
        every { userRepository.existsById(customerId) } returns true
        every { companyCustomerRepository.findByCompanyIdAndUserId(companyId, customerId) } returns existing
        every { companyCustomerRepository.save(any()) } answers { firstArg() }

        // WHEN
        customerService.setCustomerNotes(customerId, companyId, "Nowa notatka")

        // THEN
        assertEquals("Nowa notatka", existing.notes)
        verify(exactly = 1) { companyCustomerRepository.save(existing) }
    }

    @Test
    fun `setCustomerNotes normalises blank string to null`() {
        // GIVEN
        every { userRepository.existsById(customerId) } returns true
        every { companyCustomerRepository.findByCompanyIdAndUserId(companyId, customerId) } returns null
        every { companyCustomerRepository.save(any()) } answers { firstArg() }

        // WHEN
        customerService.setCustomerNotes(customerId, companyId, "   ")

        // THEN
        verify(exactly = 1) { companyCustomerRepository.save(match { it.notes == null }) }
    }

    @Test
    fun `setCustomerNotes throws NoSuchElementException for unknown customer`() {
        // GIVEN
        every { userRepository.existsById(customerId) } returns false

        // WHEN & THEN
        assertThrows<NoSuchElementException> {
            customerService.setCustomerNotes(customerId, companyId, "Notatka")
        }
        verify(exactly = 0) { companyCustomerRepository.save(any()) }
    }
}
