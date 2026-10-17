package pl.kacosmetology.scheduler.user

import io.mockk.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import pl.kacosmetology.scheduler.company.*
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.testId
import pl.kacosmetology.scheduler.user.dto.ChangeStaffPasswordRequest
import pl.kacosmetology.scheduler.user.dto.SetStaffPasswordRequest

class StaffAccountServiceTest {
    private val accounts = mockk<CompanyEmployeeRepository>()
    private val companies = mockk<CompanyRepository>()
    private val links = mockk<StaffPasswordLinkRepository>()
    private val passwords = mockk<PasswordEncoder>()
    private val reservations = mockk<ReservationRepository>()
    private val events = mockk<ApplicationEventPublisher>()
    private val now = Instant.parse("2026-10-15T10:00:00Z")
    private val service =
        StaffAccountService(
            accounts,
            companies,
            links,
            passwords,
            reservations,
            Clock.fixed(now, ZoneId.of("Europe/Warsaw")),
            events,
        )
    private val employee =
        CompanyEmployee(
            id = testId(5),
            companyId = testId(1),
            userId = testId(10),
            role = "EMPLOYEE",
            passwordHash = "old-hash",
        )

    @Test
    fun `incorrect current password cannot change credentials revoke or create session`() {
        every { accounts.findAccountForUpdate(testId(5)) } returns employee
        every { passwords.matches("wrongpass", "old-hash") } returns false
        assertThrows<BadCredentialsException> {
            service.changePassword(testId(5), ChangeStaffPasswordRequest("wrongpass", "password456"))
        }
        assertEquals("old-hash", employee.passwordHash)
        assertEquals(0L, employee.authVersion)
        verify {
            events wasNot Called
            links wasNot Called
        }
    }

    @Test
    fun `password update increments durable version before after commit revocation event`() {
        every { accounts.findAccountForUpdate(testId(5)) } returns employee
        every { passwords.matches("password123", "old-hash") } returns true
        every { passwords.encode("password456") } returns "new-hash"
        every { links.deleteById(testId(5)) } just Runs
        every { events.publishEvent(any<StaffAccountRevoked>()) } answers { assertEquals(1L, employee.authVersion) }
        service.changePassword(testId(5), ChangeStaffPasswordRequest("password123", "password456"))
        assertEquals("new-hash", employee.passwordHash)
        verify(exactly = 1) { events.publishEvent(StaffAccountRevoked(testId(5))) }
    }

    @Test
    fun `disabling acquires company before staff lock invalidates links and preserves bookings`() {
        every { companies.findByIdForUpdate(testId(1)) } returns Company(id = testId(1), name = "Salon")
        every { accounts.findByCompanyIdAndUserIdForUpdate(testId(1), testId(10)) } returns employee
        every { links.deleteById(testId(5)) } just Runs
        every { events.publishEvent(any<StaffAccountRevoked>()) } just Runs
        every { reservations.findEmployeeSchedule(testId(1), testId(10), any(), any()) } returns emptyList()
        service.setEnabled(testId(1), testId(10), false)
        assertFalse(employee.enabled)
        assertEquals(1L, employee.authVersion)
        verifyOrder {
            companies.findByIdForUpdate(testId(1))
            accounts.findByCompanyIdAndUserIdForUpdate(testId(1), testId(10))
            links.deleteById(testId(5))
            events.publishEvent(StaffAccountRevoked(testId(5)))
        }
    }

    @Test
    fun `token expires at exact elapsed deadline without changing credentials`() {
        every { links.findByTokenHash(any()) } returns StaffPasswordLink(testId(5), "hash", now)
        every { accounts.findAccountForUpdate(testId(5)) } returns employee
        assertThrows<ApiException> { service.setPassword(SetStaffPasswordRequest("token", "password456")) }
        assertEquals("old-hash", employee.passwordHash)
        verify(exactly = 0) { links.delete(any()) }
        verify { events wasNot Called }
    }
}
