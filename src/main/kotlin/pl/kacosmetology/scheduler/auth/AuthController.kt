package pl.kacosmetology.scheduler.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.kacosmetology.scheduler.auth.dto.AuthResponse
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginResponse
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.security.CustomUserDetails

/**
 * REST API for customer (SMS OTP) and staff (email/password) authentication, and session logout.
 * Client IP for rate limiting is `remoteAddr`; `X-Forwarded-For` is applied by the servlet container only
 * when the direct peer matches `TRUSTED_PROXIES`.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService
) {

    /** Sends an OTP code via SMS to the given phone number. */
    @PostMapping("/request-code")
    fun requestCode(
        @Valid @RequestBody request: RequestCodeRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Map<String, String>> {
        authService.requestCode(request, httpRequest.remoteAddr)
        return ResponseEntity.ok(mapOf("message" to "Kod wysłany!"))
    }

    /** Verifies the OTP code and returns a JWT token. Registers a new user on first login. */
    @PostMapping("/verify-code")
    fun verifyCode(
        @Valid @RequestBody request: VerifyCodeRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<AuthResponse> {
        return ResponseEntity.ok(authService.verifyCode(request, httpRequest.remoteAddr))
    }

    /** Authenticates staff or returns their employments when an explicit employment selection is required. */
    @PostMapping("/login-staff")
    fun loginStaff(
        @Valid @RequestBody request: StaffLoginRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<StaffLoginResponse> {
        return ResponseEntity.ok(authService.loginStaff(request, httpRequest.remoteAddr))
    }

    /** Revokes the session of the token used for this request. */
    @PostMapping("/logout")
    fun logout(@AuthenticationPrincipal userDetails: CustomUserDetails): ResponseEntity<Void> {
        authService.logout(userDetails)
        return ResponseEntity.noContent().build()
    }

    /** Revokes every session of the authenticated user. */
    @PostMapping("/logout-all")
    fun logoutAll(@AuthenticationPrincipal userDetails: CustomUserDetails): ResponseEntity<Void> {
        authService.logoutAll(userDetails)
        return ResponseEntity.noContent().build()
    }
}
