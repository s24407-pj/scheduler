package pl.kacosmetology.scheduler.security

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import pl.kacosmetology.scheduler.testId

class AccountSessionRegistryTest {
    private val redis = mockk<StringRedisTemplate>()
    private val values = mockk<ValueOperations<String, String>>()
    private val sessions = mockk<FindByIndexNameSessionRepository<Session>>()
    private val login = Instant.parse("2026-10-15T10:00:00Z")
    private val customer = CustomUserDetails(testId(12), "+48500100200", "ROLE_CUSTOMER", testId(1))
    private val staff = CustomUserDetails(testId(12), "+48500100200", "ROLE_EMPLOYEE", testId(8), testId(31))
    private var generation = 0L

    init {
        every { redis.opsForValue() } returns values
        every { values.get(any()) } answers { generation.toString() }
        every { values.increment(any()) } answers { ++generation }
        every { sessions.findByPrincipalName(any()) } returns emptyMap()
    }

    @Test
    fun `customer deadline is absolute elapsed time and expires at exact boundary`() {
        val initial = registry(login)
        val attributes = initial.sessionAttributes(customer)
        assertTrue(registry(login.plus(Duration.ofHours(24)).minusNanos(1)).isAuthorized(customer, attributes::get))
        assertFalse(registry(login.plus(Duration.ofHours(24))).isAuthorized(customer, attributes::get))
        assertFalse(registry(login.plus(Duration.ofHours(25))).isAuthorized(customer, attributes::get))
    }

    @Test
    fun `staff and customer use different account indexes even with same legacy user`() {
        val registry = registry(login)
        val customerAttributes = registry.sessionAttributes(customer)
        val staffAttributes =
            registry.sessionAttributes(staff) + (AccountSessionRegistry.MFA_ATTRIBUTE to staff.authVersion)
        assertEquals(
            "customer:${testId(1)}:${testId(12)}:0",
            customerAttributes[FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME],
        )
        assertEquals(
            "staff:${testId(31)}:0",
            staffAttributes[FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME],
        )
        assertFalse(registry.isAuthorized(staff, customerAttributes::get))
        assertFalse(staffAttributes.containsKey(AccountSessionRegistry.CUSTOMER_DEADLINE_ATTRIBUTE))
        assertTrue(registry(login.plus(Duration.ofDays(2))).isAuthorized(staff, staffAttributes::get))
    }

    @Test
    fun `revocation precedes cleanup and prevents old generation authorization`() {
        val registry = registry(login)
        val old = registry.sessionAttributes(staff) + (AccountSessionRegistry.MFA_ATTRIBUTE to staff.authVersion)
        every { sessions.findByPrincipalName("staff:${testId(31)}:0") } answers
            {
                assertFalse(registry.isAuthorized(staff, old::get))
                emptyMap()
            }
        registry.revokeAccount(staff.accountKey())
        val fresh = registry.sessionAttributes(staff) + (AccountSessionRegistry.MFA_ATTRIBUTE to staff.authVersion)
        assertTrue(registry.isAuthorized(staff, fresh::get))
        assertEquals("staff:${testId(31)}:1", fresh[FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME])
        verify(exactly = 1) { sessions.findByPrincipalName("staff:${testId(31)}:0") }
    }

    @Test
    fun `cleanup failure cannot undo revocation`() {
        val registry = registry(login)
        val old = registry.sessionAttributes(staff) + (AccountSessionRegistry.MFA_ATTRIBUTE to staff.authVersion)
        every { sessions.findByPrincipalName(any()) } throws IllegalStateException("cleanup unavailable")
        assertThrows(IllegalStateException::class.java) { registry.revokeAccount(staff.accountKey()) }
        assertFalse(registry.isAuthorized(staff, old::get))
    }

    @Test
    fun `password verification attributes cannot authorize staff without second factor`() {
        val registry = registry(login)
        val attributes = registry.sessionAttributes(staff)
        assertFalse(registry.isAuthorized(staff, attributes::get))
        assertTrue(registry.isCurrent(staff, attributes::get))
    }

    @Test
    fun `missing generation or customer deadline cannot authorize legacy sessions`() {
        val registry = registry(login)
        val attributes = registry.sessionAttributes(customer)
        assertFalse(registry.isAuthorized(customer, (attributes - AccountSessionRegistry.GENERATION_ATTRIBUTE)::get))
        assertFalse(
            registry.isAuthorized(customer, (attributes - AccountSessionRegistry.CUSTOMER_DEADLINE_ATTRIBUTE)::get),
        )
    }

    private fun registry(now: Instant) = AccountSessionRegistry(redis, sessions, Clock.fixed(now, ZoneOffset.UTC))
}
