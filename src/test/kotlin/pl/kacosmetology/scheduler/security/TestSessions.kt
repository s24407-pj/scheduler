package pl.kacosmetology.scheduler.security

import jakarta.servlet.http.Cookie
import java.util.*
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.session.data.redis.RedisIndexedSessionRepository
import org.springframework.stereotype.Component
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.user.CompanyCustomer

/** Real Redis-session fixtures for feature tests; login/session boundary tests exercise the REST endpoints. */
@Component
class TestSessions(
    private val repository: RedisIndexedSessionRepository,
    private val accountSessions: AccountSessionRegistry,
    private val staffAccounts: pl.kacosmetology.scheduler.company.CompanyEmployeeRepository,
) {
    fun customer(user: CompanyCustomer): Cookie =
        create(CustomUserDetails(requireNotNull(user.id), user.phoneNumber, "ROLE_CUSTOMER", user.companyId))

    fun staff(user: StaffFixture, employment: CompanyEmployee): Cookie {
        require(employment.userId == user.id)
        // Test-only adapter for legacy feature fixtures. Runtime never obtains staff data from customer accounts.
        val local = staffAccounts.findById(requireNotNull(employment.id)).orElseThrow()
        if (local.loginEmail == null) {
            local.loginEmail = user.email?.trim()?.lowercase() ?: "fixture-${local.id}@example.test"
            local.passwordHash = user.passwordHash
            local.firstName = user.firstName
            local.lastName = user.lastName
            local.contactPhone = user.phoneNumber
            local.photoUrl = user.photoUrl
            staffAccounts.saveAndFlush(local)
        }
        return create(
            CustomUserDetails(
                requireNotNull(user.id),
                requireNotNull(local.loginEmail),
                "ROLE_${employment.role}",
                employment.companyId,
                requireNotNull(employment.id),
                local.authVersion,
            ),
        )
    }

    private fun create(principal: CustomUserDetails): Cookie {
        val session = repository.createSession()
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities)
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context)
        session.maxInactiveInterval = AccountSessionRegistry.SESSION_DURATION
        accountSessions.sessionAttributes(principal).forEach { (name, value) -> session.setAttribute(name, value) }
        // The concrete Redis repository owns the same Session subtype returned by createSession.
        repository.save(session)
        return FixtureSessionCookie(Base64.getEncoder().encodeToString(session.id.toByteArray()), principal)
    }
}

/** Carries the client-side scope snapshot alongside a real Redis cookie for feature-test requests. */
class FixtureSessionCookie(value: String, val principal: CustomUserDetails) : Cookie("SESSION", value)
