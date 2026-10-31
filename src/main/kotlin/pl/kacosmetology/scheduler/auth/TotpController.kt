package pl.kacosmetology.scheduler.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import pl.kacosmetology.scheduler.auth.dto.*
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.SessionAuthenticationService

@RestController
@RequestMapping("/api/auth/totp")
class TotpController(private val totp: TotpService, private val authentication: SessionAuthenticationService) {
    @PostMapping("/setup")
    fun setup(@Valid @RequestBody body: TotpChallengeRequest, request: HttpServletRequest): TotpSetupResponse {
        authentication.requireAnonymous()
        return totp.setup(body.challengeToken, request.getSession(false)?.id, request.remoteAddr)
    }

    @PostMapping("/verify")
    fun verify(
        @Valid @RequestBody body: TotpVerifyRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): StaffLoginResponse {
        authentication.requireAnonymous()
        return finish(
            totp.verify(body.challengeToken, body.code, request.getSession(false)?.id, request.remoteAddr),
            request,
            response,
        )
    }

    @PostMapping("/setup/confirm")
    fun confirm(
        @Valid @RequestBody body: TotpConfirmRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): StaffLoginResponse {
        authentication.requireAnonymous()
        return finish(
            totp.confirm(body.setupToken, body.code, request.getSession(false)?.id, request.remoteAddr),
            request,
            response,
        )
    }

    @PostMapping("/recover")
    fun recover(@Valid @RequestBody body: TotpRecoverRequest, request: HttpServletRequest): StaffLoginResponse {
        authentication.requireAnonymous()
        return totp.recover(body.challengeToken, body.recoveryCode, request.getSession(false)?.id, request.remoteAddr)
    }

    private fun finish(
        result: StaffAuthenticationResult,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): StaffLoginResponse {
        authentication.authenticate(result.principal, request, response, result.sessionAttributes)
        return result.response
    }
}

@RestController
@RequestMapping("/api/employees")
class EmployeeTotpController(private val totp: TotpService) {
    @PostMapping("/{id}/totp/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun reset(
        @PathVariable id: UUID,
        @AuthenticationPrincipal owner: CustomUserDetails,
        @Valid @RequestBody body: TotpResetRequest,
        request: HttpServletRequest,
    ) {
        totp.reset(owner, id, body, request.remoteAddr)
    }
}
