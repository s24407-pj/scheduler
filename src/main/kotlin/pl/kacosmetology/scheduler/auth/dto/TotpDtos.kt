package pl.kacosmetology.scheduler.auth.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

private const val TOKEN = "^[A-Za-z0-9_-]{43}$"

data class TotpChallengeRequest(@field:Pattern(regexp = TOKEN) val challengeToken: String)

data class TotpVerifyRequest(
    @field:Pattern(regexp = TOKEN) val challengeToken: String,
    @field:Pattern(regexp = "^[0-9]{6}$", message = "Kod musi mieć dokładnie 6 cyfr") val code: String,
)

data class TotpConfirmRequest(
    @field:Pattern(regexp = TOKEN) val setupToken: String,
    @field:Pattern(regexp = "^[0-9]{6}$", message = "Kod musi mieć dokładnie 6 cyfr") val code: String,
)

data class TotpRecoverRequest(
    @field:Pattern(regexp = TOKEN) val challengeToken: String,
    @field:NotBlank @field:Size(max = 64) val recoveryCode: String,
)

data class TotpResetRequest(
    @field:NotBlank @field:Size(max = 128) val currentPassword: String,
    @field:Pattern(regexp = "^[0-9]{6}$", message = "Kod musi mieć dokładnie 6 cyfr") val code: String,
)

data class TotpSetupResponse(
    val setupToken: String,
    val secret: String,
    val otpauthUri: String,
    val expiresAt: Instant,
    val recoveryCodes: List<String>?,
)
