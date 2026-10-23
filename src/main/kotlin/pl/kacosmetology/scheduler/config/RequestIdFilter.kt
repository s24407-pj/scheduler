package pl.kacosmetology.scheduler.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.*
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Tags every request with an ID stored in the logging MDC under [MDC_KEY] and echoed in the [HEADER] response header,
 * so all log lines of one request can be correlated. A well-formed incoming [HEADER] (for example from a reverse proxy)
 * is reused; anything else is replaced with a random UUID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {

    companion object {
        /** Request and response header carrying the request ID. */
        const val HEADER = "X-Request-Id"

        /** MDC key under which the request ID is exposed to log patterns and structured logs. */
        const val MDC_KEY = "requestId"

        private val VALID_ID = Regex("^[A-Za-z0-9-]{1,64}$")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader(HEADER)?.takeIf { VALID_ID.matches(it) } ?: UUID.randomUUID().toString()
        MDC.put(MDC_KEY, requestId)
        response.setHeader(HEADER, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }
}
