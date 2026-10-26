package pl.kacosmetology.scheduler.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.web.filter.OncePerRequestFilter

/** Rejects stale identities before CSRF/authorization; never upgrades or switches a stored employment scope. */
class SessionAuthorizationFilter(
    private val userDetailsService: CustomUserDetailsService,
    private val logoutHandler: SecurityContextLogoutHandler,
    private val accountSessions: AccountSessionRegistry,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication != null && authentication.isAuthenticated) {
            val principal = authentication.principal as? CustomUserDetails
            val current =
                try {
                    when {
                        principal == null -> null
                        principal.role == "ROLE_CUSTOMER" &&
                            principal.companyId != null &&
                            principal.employmentId == null ->
                            userDetailsService.loadCustomer(principal.username, principal.companyId, principal.id)
                        principal.role in setOf("ROLE_OWNER", "ROLE_EMPLOYEE") && principal.employmentId != null ->
                            userDetailsService.loadStaffByEmployment(principal.username, principal.employmentId)
                        else -> null
                    }
                } catch (_: UsernameNotFoundException) {
                    null
                }
            if (
                current != principal ||
                    principal == null ||
                    authentication.authorities != principal.authorities ||
                    !accountSessions.isAuthorized(principal) { request.getSession(false)?.getAttribute(it) }
            ) {
                logoutHandler.logout(request, response, authentication)
            }
        }
        chain.doFilter(request, response)
    }
}
