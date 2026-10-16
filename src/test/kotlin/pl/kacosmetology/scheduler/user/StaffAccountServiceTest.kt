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
        CompanyEmployee(id = 5L, companyId = 1L, userId = 10L, role = "EMPLOYEE", passwordHash = "old-hash")

    @Test
    fun `incorrect current password cannot change credentials revoke or create session`() {
        every { accounts.findAccountForUpdate(5L) } returns employee
        every { passwords.matches("wrongpass", "old-hash") } returns false
        assertThrows<BadCredentialsException> {
            service.changePassword(5L, ChangeStaffPasswordRequest("wrongpass", "password456"))
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
        every { accounts.findAccountForUpdate(5L) } returns employee
        every { passwords.matches("password123", "old-hash") } returns true
        every { passwords.encode("password456") } returns "new-hash"
        every { links.deleteById(5L) } just Runs
        every { events.publishEvent(any<StaffAccountRevoked>()) } answers { assertEquals(1L, employee.authVersion) }
        service.changePassword(5L, ChangeStaffPasswordRequest("password123", "password456"))
        assertEquals("new-hash", employee.passwordHash)
        verify(exactly = 1) { events.publishEvent(StaffAccountRevoked(5L)) }
    }

    @Test
    fun `disabling acquires company before staff lock invalidates links and preserves bookings`() {
        every { companies.findByIdForUpdate(1L) } returns Company(id = 1L, name = "Salon")
        every { accounts.findByCompanyIdAndUserIdForUpdate(1L, 10L) } returns employee
        every { links.deleteById(5L) } just Runs
        every { events.publishEvent(any<StaffAccountRevoked>()) } just Runs
        every { reservations.findEmployeeSchedule(1L, 10L, any(), any()) } returns emptyList()
        service.setEnabled(1L, 10L, false)
        assertFalse(employee.enabled)
        assertEquals(1L, employee.authVersion)
        verifyOrder {
            companies.findByIdForUpdate(1L)
            accounts.findByCompanyIdAndUserIdForUpdate(1L, 10L)
            links.deleteById(5L)
            events.publishEvent(StaffAccountRevoked(5L))
        }
    }

    @Test
    fun `token expires at exact elapsed deadline without changing credentials`() {
        every { links.findByTokenHash(any()) } returns StaffPasswordLink(5L, "hash", now)
        every { accounts.findAccountForUpdate(5L) } returns employee
        assertThrows<ApiException> { service.setPassword(SetStaffPasswordRequest("token", "password456")) }
        assertEquals("old-hash", employee.passwordHash)
        verify(exactly = 0) { links.delete(any()) }
        verify { events wasNot Called }
    }
}
