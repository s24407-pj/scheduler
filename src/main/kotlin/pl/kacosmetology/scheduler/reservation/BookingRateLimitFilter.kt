package pl.kacosmetology.scheduler.reservation

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Duration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerExceptionResolver
import pl.kacosmetology.scheduler.auth.RateLimitExceededException
import pl.kacosmetology.scheduler.auth.RedisRollingRateLimiter
import pl.kacosmetology.scheduler.security.CustomUserDetails

/** Counts all booking submissions, including validation failures and idempotent retries. */
@Component
class BookingRateLimitFilter(
    private val limits: RedisRollingRateLimiter,
    @param:Qualifier("handlerExceptionResolver") private val errors: HandlerExceptionResolver,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.method == "POST" && request.servletPath in setOf("/api/reservations", "/api/reservations/staff")) {
            val ip = limits.check("rate:booking:ip:${request.remoteAddr}", 60, Duration.ofMinutes(1))
            val account = SecurityContextHolder.getContext().authentication?.principal as? CustomUserDetails
            val user = account?.let {
                limits.check("rate:booking:account:${it.accountKey()}", 10, Duration.ofMinutes(1))
            }
            if (!ip.allowed || user?.allowed == false) {
                errors.resolveException(
                    request,
                    response,
                    null,
                    RateLimitExceededException(
                        retryAfterSeconds = maxOf(ip.retryAfterSeconds, user?.retryAfterSeconds ?: 0),
                    ),
                )
                return
            }
        }
        chain.doFilter(request, response)
    }
}
