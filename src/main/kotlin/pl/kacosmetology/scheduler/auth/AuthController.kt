package pl.kacosmetology.scheduler.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.kacosmetology.scheduler.auth.dto.AuthResponse
import pl.kacosmetology.scheduler.auth.dto.AuthenticatedSessionResponse
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginResponse
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.SessionAuthenticationService

/**
 * REST API for customer (SMS OTP) and staff (email/password) authentication, and session logout. Client IP for rate
 * limiting is `remoteAddr`; `X-Forwarded-For` is applied by the servlet container only when the direct peer matches
 * `TRUSTED_PROXIES`.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val sessionAuthenticationService: SessionAuthenticationService,
) {

    /** Sends an OTP code via SMS to the given phone number. */
    @PostMapping("/request-code")
    fun requestCode(
        @Valid @RequestBody request: RequestCodeRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<Map<String, String>> {
        authService.requestCode(request, httpRequest.remoteAddr)
        return ResponseEntity.ok(mapOf("message" to "Kod wysłany!"))
    }

    /** Verifies OTP, registers a new user if needed, and creates the Spring Security session. */
    @PostMapping("/verify-code")
    fun verifyCode(
        @Valid @RequestBody request: VerifyCodeRequest,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<AuthResponse> {
        sessionAuthenticationService.requireAnonymous()
        val principal = authService.verifyCode(request, httpRequest.remoteAddr)
        sessionAuthenticationService.authenticate(principal, httpRequest, httpResponse)
        return ResponseEntity.ok(AuthResponse())
    }

    /** Authenticates one company-owned staff account with company, normalized email and password. */
    @PostMapping("/login-staff")
    fun loginStaff(
        @Valid @RequestBody request: StaffLoginRequest,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): ResponseEntity<StaffLoginResponse> {
        sessionAuthenticationService.requireAnonymous()
        val result = authService.loginStaff(request, httpRequest.remoteAddr)
        sessionAuthenticationService.authenticate(result.principal, httpRequest, httpResponse, result.sessionAttributes)
        return ResponseEntity.ok(result.response)
    }

    /** Supplies a fresh SPA CSRF token, including to anonymous clients before login. */
    @GetMapping("/csrf")
    fun csrf(request: HttpServletRequest): Map<String, String> = sessionAuthenticationService.csrf(request)

    /** Returns identity and scope for clients that previously decoded credential claims. */
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: CustomUserDetails): AuthenticatedSessionResponse =
        AuthenticatedSessionResponse(
            principal.id,
            principal.username,
            principal.authorities.map { it.authority },
            principal.companyId,
            principal.employmentId,
        )

    /** Invalidates the current session and expires its cookies. Requires CSRF, including on repeated logout. */
    @PostMapping("/logout")
    fun logout(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Void> {
        sessionAuthenticationService.logout(request, response)
        return ResponseEntity.noContent().build()
    }

    /** Revokes all devices of this customer or company-owned staff account only. */
    @PostMapping("/logout-all")
    fun logoutAll(
        @AuthenticationPrincipal principal: CustomUserDetails,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        sessionAuthenticationService.logoutAll(principal, request, response)
        return ResponseEntity.noContent().build()
    }
}
