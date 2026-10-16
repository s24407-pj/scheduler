package pl.kacosmetology.scheduler.user

import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.user.dto.UpdateCustomerProfileRequest

class CustomerServiceTest {
    private val users = mockk<UserRepository>()
    private val reservations = mockk<ReservationRepository>()
    private val blocks = mockk<CompanyCustomerBlockRepository>()
    private val profiles = mockk<CompanyCustomerRepository>()
    private val blocking = mockk<CustomerBlockingPolicy>()
    private val service = CustomerService(users, reservations, blocks, profiles, blocking)

    @Test
    fun `list uses company owned names and contact rather than global account presentation`() {
        val profile =
            CompanyCustomer(
                companyId = 1,
                userId = 2,
                firstName = "Local",
                lastName = "Name",
                contactEmail = "local@example.test",
                notes = "private",
            )
        every { profiles.findByCompanyId(1) } returns listOf(profile)
        every { users.findAllById(listOf(2L)) } returns
            listOf(User(id = 2, phoneNumber = "+48500100200", firstName = "Global", lastName = "Wrong"))
        every { blocks.findByCompanyId(1) } returns emptyList()
        every { blocking.currentCount(1, 2) } returns 2
        val result = service.listCustomers(1).single()
        assertEquals(2, result.noShowCount)
        assertEquals("Local", result.firstName)
        assertEquals("private", result.notes)
        assertEquals("local@example.test", result.contactEmail)
    }

    @Test
    fun `foreign customer lookup cannot retrieve global identity`() {
        every { profiles.findByCompanyIdAndUserId(1, 2) } returns null
        assertThrows<NoSuchElementException> { service.getCustomerStatus(2, 1) }
        verify(exactly = 0) { users.findById(any()) }
    }

    @Test
    fun `notes cannot establish company relationship`() {
        every { profiles.findByCompanyIdAndUserId(1, 2) } returns null
        assertThrows<NoSuchElementException> { service.setCustomerNotes(2, 1, "intrusion") }
        verify(exactly = 0) { profiles.save(any()) }
    }

    @Test
    fun `editing local profile leaves global authentication unchanged`() {
        val local = CompanyCustomer(companyId = 1, userId = 2, firstName = "Before", lastName = "Name")
        every { profiles.findByCompanyIdAndUserId(1, 2) } returns local
        service.updateProfile(2, 1, UpdateCustomerProfileRequest("After", "Name", "local@example.test"))
        assertEquals("After", local.firstName)
        verify { users wasNot Called }
    }
}
