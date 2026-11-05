package pl.kacosmetology.scheduler.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerExceptionResolver
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode

/** Binds a company-scoped write to its form's account scope, matching decoded paths with the same rules as MVC. */
class ExpectedSessionContextFilter(private val exceptionResolver: HandlerExceptionResolver) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? CustomUserDetails
        if (principal != null && requiresContext(request)) {
            val supplied = request.getHeader(HEADER)
            if (principal.role in STAFF_ROLES || principal.role == "ROLE_CUSTOMER" || supplied != null) {
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
        private val FORMAT =
            Regex("v1:$UUID_PATTERN:$UUID_PATTERN:(?:$UUID_PATTERN:ROLE_(OWNER|EMPLOYEE)|null:ROLE_CUSTOMER)")
        private val API = pathPattern("/api/**")
        private val PUBLIC_AUTH = pathPattern("/api/auth/**")
        private val LOGOUT_ALL = pathPattern("/api/auth/logout-all")

        /** Public authentication and recovery actions do not originate in an authenticated staff form. */
        private fun requiresContext(request: HttpServletRequest): Boolean =
            request.method !in SAFE_METHODS &&
                API.matches(request) &&
                (!PUBLIC_AUTH.matches(request) || LOGOUT_ALL.matches(request))

        /** Public identity fields form an equality precondition, never an authentication credential. */
        fun context(principal: CustomUserDetails): String =
            "v1:${principal.id}:${principal.companyId}:${principal.employmentId}:${principal.role}"
    }
}
