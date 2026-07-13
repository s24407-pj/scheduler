package pl.kacosmetology.scheduler.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.kacosmetology.scheduler.auth.dto.AuthResponse
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginResponse
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest

/** REST API for customer (SMS OTP) and staff (email/password) authentication. */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService
) {

    /** Sends an OTP code via SMS to the given phone number. */
    @PostMapping("/request-code")
    fun requestCode(@Valid @RequestBody request: RequestCodeRequest): ResponseEntity<Map<String, String>> {
        authService.requestCode(request)
        return ResponseEntity.ok(mapOf("message" to "Kod wysłany!"))
    }

    /** Verifies the OTP code and returns a JWT token. Registers a new user on first login. */
    @PostMapping("/verify-code")
    fun verifyCode(
        @Valid @RequestBody request: VerifyCodeRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<AuthResponse> {
        return ResponseEntity.ok(authService.verifyCode(request, clientIp(httpRequest)))
    }

    /** Authenticates staff or returns their employments when an explicit employment selection is required. */
    @PostMapping("/login-staff")
    fun loginStaff(
        @Valid @RequestBody request: StaffLoginRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<StaffLoginResponse> {
        return ResponseEntity.ok(authService.loginStaff(request, clientIp(httpRequest)))
    }

    // TODO: Before deployment, configure trusted-proxy forwarding or use remoteAddr exclusively;
    //      never trust a client-supplied X-Forwarded-For header on a directly accessible app.
    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.split(",")?.first()?.trim()
            ?: request.remoteAddr
}
