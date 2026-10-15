package pl.kacosmetology.scheduler.security

import jakarta.servlet.http.Cookie
import java.util.Base64
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.session.data.redis.RedisIndexedSessionRepository
import org.springframework.stereotype.Component
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.user.User

/** Real Redis-session fixtures for feature tests; login/session boundary tests exercise the REST endpoints. */
@Component
class TestSessions(
    private val repository: RedisIndexedSessionRepository,
    private val accountSessions: AccountSessionRegistry,
) {
    fun customer(user: User): Cookie =
        create(CustomUserDetails(requireNotNull(user.id), user.phoneNumber, "ROLE_CUSTOMER"))

    fun staff(user: User, employment: CompanyEmployee): Cookie {
        require(employment.userId == user.id)
        return create(
            CustomUserDetails(
                requireNotNull(user.id),
                user.phoneNumber,
                "ROLE_${employment.role}",
                employment.companyId,
                requireNotNull(employment.id),
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
        return Cookie("SESSION", Base64.getEncoder().encodeToString(session.id.toByteArray()))
    }
}
