package pl.kacosmetology.scheduler.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Intercepts every HTTP request to extract and validate the JWT token from the Authorization header.
 * Sets the SecurityContext only if the token is valid, its session is still active in [SessionStore],
 * and its scope matches the current user and employment.
 */
@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val userDetailsService: CustomUserDetailsService,
    private val sessionStore: SessionStore
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authHeader = request.getHeader("Authorization")

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response)
            return
        }

        val claims = try {
            jwtService.parse(authHeader.substring(7))
        } catch (_: Exception) {
            null
        }

        if (claims != null && SecurityContextHolder.getContext().authentication == null) {
            val userDetails = try {
                loadScopedUserDetails(claims)
            } catch (_: Exception) {
                null
            }

            if (userDetails != null) {
                val authToken = UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.authorities
                )
                authToken.details = WebAuthenticationDetailsSource().buildDetails(request)

                SecurityContextHolder.getContext().authentication = authToken
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun loadScopedUserDetails(claims: TokenClaims): CustomUserDetails? {
        val sessionId = claims.sessionId ?: return null
        val role = claims.role ?: return null

        val details = if (role == "customer") {
            if (claims.companyId != null || claims.employmentId != null) return null
            userDetailsService.loadUserByUsername(claims.username) as CustomUserDetails
        } else {
            if (role !in setOf("owner", "employee") || claims.companyId == null || claims.employmentId == null) {
                return null
            }
            val staff = userDetailsService.loadStaffByEmployment(claims.username, claims.employmentId)
            val currentRole = staff.authorities.singleOrNull()?.authority
                ?.removePrefix("ROLE_")?.lowercase()
            if (staff.companyId != claims.companyId || staff.employmentId != claims.employmentId || currentRole != role) {
                return null
            }
            staff
        }

        if (!sessionStore.isActive(sessionId, details.id)) return null
        return details.withSession(sessionId)
    }
}
