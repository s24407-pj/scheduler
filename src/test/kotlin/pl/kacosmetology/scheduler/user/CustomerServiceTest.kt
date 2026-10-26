package pl.kacosmetology.scheduler.user

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.testId
import pl.kacosmetology.scheduler.user.dto.UpdateCustomerProfileRequest

class CustomerServiceTest {
    private val blocks = mockk<CompanyCustomerBlockRepository>()
    private val profiles = mockk<CompanyCustomerRepository>()
    private val blocking = mockk<CustomerBlockingPolicy>()
    private val service = CustomerService(blocks, profiles, blocking)

    @Test
    fun `list uses company owned names and contact rather than global account presentation`() {
        val profile =
            CompanyCustomer(
                companyId = testId(1),
                id = testId(2),
                phoneNumber = "+48500100200",
                firstBookingAt = java.time.Instant.EPOCH,
                firstName = "Local",
                lastName = "Name",
                contactEmail = "local@example.test",
                notes = "private",
            )
        every { profiles.findByCompanyIdAndFirstBookingAtIsNotNull(testId(1)) } returns listOf(profile)
        every { blocks.findByCompanyId(testId(1)) } returns emptyList()
        every { blocking.currentCount(testId(1), testId(2)) } returns 2
        val result = service.listCustomers(testId(1)).single()
        assertEquals(2, result.noShowCount)
        assertEquals("Local", result.firstName)
        assertEquals("private", result.notes)
        assertEquals("local@example.test", result.contactEmail)
    }

    @Test
    fun `foreign customer lookup cannot retrieve global identity`() {
        every { profiles.findByCompanyIdAndIdAndFirstBookingAtIsNotNull(testId(1), testId(2)) } returns null
        assertThrows<NoSuchElementException> { service.getCustomerStatus(testId(2), testId(1)) }
    }

    @Test
    fun `notes cannot establish company relationship`() {
        every { profiles.findByCompanyIdAndIdAndFirstBookingAtIsNotNull(testId(1), testId(2)) } returns null
        assertThrows<NoSuchElementException> { service.setCustomerNotes(testId(2), testId(1), "intrusion", 0) }
        verify(exactly = 0) { profiles.save(any()) }
    }

    @Test
    fun `stale notes revision leaves existing note unchanged`() {
        val local =
            CompanyCustomer(
                    companyId = testId(1),
                    id = testId(2),
                    phoneNumber = "+48500100200",
                    firstBookingAt = java.time.Instant.EPOCH,
                    notes = "Existing note",
                )
                .apply { version = 1 }
        every { profiles.findByCompanyIdAndIdAndFirstBookingAtIsNotNull(testId(1), testId(2)) } returns local
        val error =
            assertThrows<ApiException> {
                service.setCustomerNotes(testId(2), testId(1), "Stale write", 0)
            }
        assertEquals(ErrorCode.CONCURRENT_MODIFICATION, error.code)
        assertEquals("Existing note", local.notes)
        verify(exactly = 0) { profiles.save(any()) }
    }

    @Test
    fun `editing local profile leaves global authentication unchanged`() {
        val local =
            CompanyCustomer(
                companyId = testId(1),
                id = testId(2),
                phoneNumber = "+48500100200",
                firstBookingAt = java.time.Instant.EPOCH,
                firstName = "Before",
                lastName = "Name",
            )
        every { profiles.findByCompanyIdAndIdAndFirstBookingAtIsNotNull(testId(1), testId(2)) } returns local
        service.updateProfile(
            testId(2),
            testId(1),
            UpdateCustomerProfileRequest("After", "Name", "local@example.test"),
            0,
        )
        assertEquals("After", local.firstName)
        assertEquals("+48500100200", local.phoneNumber)
    }
}
