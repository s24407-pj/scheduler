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
 * Intercepts every HTTP request to extract and validate the JWT token
 * from the Authorization header. Sets the SecurityContext if valid.
 */
@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val userDetailsService: CustomUserDetailsService
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

        val jwt = authHeader.substring(7)

        val userIdentifier = try {
            jwtService.extractUsername(jwt)
        } catch (_: Exception) {
            null
        }

        if (userIdentifier != null && SecurityContextHolder.getContext().authentication == null) {
            val userDetails = try {
                loadScopedUserDetails(jwt, userIdentifier)
            } catch (_: Exception) {
                null
            }

            if (userDetails != null && jwtService.isTokenValid(jwt, userDetails)) {
                val authToken = UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.authorities
                )
                authToken.details = WebAuthenticationDetailsSource().buildDetails(request)

                SecurityContextHolder.getContext().authentication = authToken
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun loadScopedUserDetails(jwt: String, username: String): CustomUserDetails? {
        val role = jwtService.extractRole(jwt) ?: return null
        val companyId = jwtService.extractCompanyId(jwt)
        val employmentId = jwtService.extractEmploymentId(jwt)

        if (role == "customer") {
            if (companyId != null || employmentId != null) return null
            return userDetailsService.loadUserByUsername(username) as CustomUserDetails
        }
        if (role !in setOf("owner", "employee") || companyId == null || employmentId == null) return null

        val details = userDetailsService.loadStaffByEmployment(username, employmentId)
        val currentRole = details.authorities.singleOrNull()?.authority
            ?.removePrefix("ROLE_")?.lowercase()
        return details.takeIf {
            it.companyId == companyId && it.employmentId == employmentId && currentRole == role
        }
    }
}
