package pl.kacosmetology.scheduler.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CsrfLogoutHandler
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.security.web.csrf.DeferredCsrfToken
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/** Bridges REST authentication to Spring Security's fixation/CSRF strategies and Redis-backed HttpSession. */
@Service
class SessionAuthenticationService(
    private val securityContextRepository: SecurityContextRepository,
    private val sessionAuthenticationStrategy: SessionAuthenticationStrategy,
    private val sessionRepository: FindByIndexNameSessionRepository<out Session>,
    private val logoutHandler: SecurityContextLogoutHandler,
    private val csrfTokenRepository: CsrfTokenRepository,
) {
    /** Existing authenticated sessions must log out before selecting a different identity or employment. */
    fun requireAnonymous() {
        if (SecurityContextHolder.getContext().authentication?.principal is CustomUserDetails) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Wyloguj się przed ponownym logowaniem")
        }
    }

    /** Rotates a preexisting session, clears pre-login CSRF, and explicitly saves a fresh authenticated context. */
    fun authenticate(principal: CustomUserDetails, request: HttpServletRequest, response: HttpServletResponse) {
        requireAnonymous()
        val authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities)
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response)
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        // Stable account ID indexes all customer/staff employments, even if the phone number changes.
        request
            .getSession(true)
            .setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principal.id.toString())
        securityContextRepository.saveContext(context, request, response)
    }

    /** Deletes only this HttpSession and clears its context and CSRF cookie; safe when already logged out. */
    fun logout(request: HttpServletRequest, response: HttpServletResponse) {
        val authentication = SecurityContextHolder.getContext().authentication
        CsrfLogoutHandler(csrfTokenRepository).logout(request, response, authentication)
        logoutHandler.logout(request, response, authentication)
    }

    /** Deletes all indexed sessions for the account, across devices and employment scopes, then clears this browser. */
    fun logoutAll(principal: CustomUserDetails, request: HttpServletRequest, response: HttpServletResponse) {
        sessionRepository.findByPrincipalName(principal.id.toString()).keys.forEach(sessionRepository::deleteById)
        logout(request, response)
    }

    /** Materializes the SPA cookie and returns its raw token for frontends on a different origin. */
    fun csrf(request: HttpServletRequest): Map<String, String> {
        val token = (request.getAttribute(DeferredCsrfToken::class.java.name) as DeferredCsrfToken).get()
        return mapOf("token" to token.token, "headerName" to token.headerName)
    }
}
