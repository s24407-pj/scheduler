package pl.kacosmetology.scheduler.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerExceptionResolver
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode

/** Binds a staff write to the account scope that opened its form, independently of the current cookie. */
class ExpectedSessionContextFilter(private val exceptionResolver: HandlerExceptionResolver) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? CustomUserDetails
        if (
            principal != null && requiresContext(request.method, request.requestURI.removePrefix(request.contextPath))
        ) {
            val supplied = request.getHeader(HEADER)
            if (principal.role in STAFF_ROLES || supplied != null) {
                val error =
                    when {
                        supplied == null ||
                            !FORMAT.matches(supplied) ||
                            request.getHeaders(HEADER).toList().size != 1 ->
                            ApiException(
                                ErrorCode.SESSION_CONTEXT_REQUIRED,
                                "Brak prawidłowego kontekstu konta. Odśwież panel przed ponownym zapisem.",
                            )
                        supplied != context(principal) ->
                            ApiException(
                                ErrorCode.SESSION_CONTEXT_CHANGED,
                                "Konto lub salon zmieniły się od otwarcia formularza. Zmiany nie zostały zapisane.",
                            )
                        else -> null
                    }
                if (error != null) {
                    exceptionResolver.resolveException(request, response, null, error)
                    return
                }
            }
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val HEADER = "X-Expected-Session-Context"
        private val STAFF_ROLES = setOf("ROLE_OWNER", "ROLE_EMPLOYEE")
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")
        private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        private val FORMAT = Regex("v1:$UUID_PATTERN:$UUID_PATTERN:$UUID_PATTERN:ROLE_(OWNER|EMPLOYEE)")

        /** Public authentication and recovery actions do not originate in an authenticated staff form. */
        fun requiresContext(method: String, path: String): Boolean =
            method !in SAFE_METHODS &&
                path.startsWith("/api/") &&
                (!path.startsWith("/api/auth/") || path == "/api/auth/logout-all")

        /** Public identity fields form an equality precondition, never an authentication credential. */
        fun context(principal: CustomUserDetails): String =
            "v1:${principal.id}:${principal.companyId}:${principal.employmentId}:${principal.role}"
    }
}
