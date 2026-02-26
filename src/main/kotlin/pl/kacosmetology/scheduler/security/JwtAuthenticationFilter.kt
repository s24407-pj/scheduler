package pl.kacosmetology.scheduler.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
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
    private val userDetailsService: UserDetailsService
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
        } catch (e: Exception) {
            null
        }

        if (userIdentifier != null && SecurityContextHolder.getContext().authentication == null) {
            val userDetails = userDetailsService.loadUserByUsername(userIdentifier)

            if (jwtService.isTokenValid(jwt, userDetails)) {
                val companyIdFromToken = try {
                    jwtService.extractCompanyId(jwt)
                } catch (e: Exception) { null }

                val originalUser = (userDetails as CustomUserDetails).user
                val enrichedUserDetails = CustomUserDetails(originalUser, companyIdFromToken, userDetails.authorities)

                val authToken = UsernamePasswordAuthenticationToken(
                    enrichedUserDetails, null, enrichedUserDetails.authorities
                )
                authToken.details = WebAuthenticationDetailsSource().buildDetails(request)

                SecurityContextHolder.getContext().authentication = authToken
            }
        }

        filterChain.doFilter(request, response)
    }
}