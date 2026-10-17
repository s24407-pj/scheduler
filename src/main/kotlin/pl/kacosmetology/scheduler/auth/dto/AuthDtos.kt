package pl.kacosmetology.scheduler.auth.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

private const val PHONE_REGEXP = "^\\s*\\+?[0-9]{9,15}\\s*$"

data class RequestCodeRequest(
    @field:NotBlank(message = "Numer telefonu jest wymagany")
    @field:Pattern(regexp = PHONE_REGEXP, message = "Nieprawidłowy format numeru telefonu")
    val phoneNumber: String,
)

data class VerifyCodeRequest(
    @field:NotBlank(message = "Numer telefonu jest wymagany")
    @field:Pattern(regexp = PHONE_REGEXP, message = "Nieprawidłowy format numeru telefonu")
    val phoneNumber: String,
    @field:NotBlank(message = "Kod SMS jest wymagany")
    @field:Size(min = 6, max = 6, message = "Kod SMS musi mieć dokładnie 6 cyfr")
    @field:Pattern(regexp = "^[0-9]{6}$", message = "Kod musi zawierać tylko cyfry")
    val code: String,
    @field:Size(max = 50, message = "Imię nie może być dłuższe niż 50 znaków") val firstName: String? = null,
    @field:Size(max = 50, message = "Nazwisko nie może być dłuższe niż 50 znaków") val lastName: String? = null,
)

data class StaffLoginRequest(
    @field:NotBlank(message = "Email jest wymagany")
    @field:Size(max = 255, message = "Email jest za długi")
    @field:Pattern(regexp = "^\\s*[^\\s@]+@[^\\s@]+\\.[^\\s@]+\\s*$", message = "Nieprawidłowy email")
    val email: String,
    @field:NotBlank(message = "Hasło jest wymagane")
    @field:Size(min = 8, message = "Hasło musi mieć co najmniej 8 znaków")
    val password: String,
    @field:NotNull(message = "Firma jest wymagana") val companyId: UUID? = null,
)

data class AuthResponse(
    val status: StaffLoginStatus = StaffLoginStatus.AUTHENTICATED,
)

/** Possible outcomes of a staff login request. */
enum class StaffLoginStatus {
    AUTHENTICATED,
}

/** Staff login result; the HttpOnly cookie, never the response body, carries authentication. */
data class StaffLoginResponse(
    val status: StaffLoginStatus,
)

/** Safe identity and exact authorization scope; the credential is never included. */
data class AuthenticatedSessionResponse(
    val userId: UUID,
    val username: String,
    val authorities: List<String>,
    val companyId: UUID?,
    val employmentId: UUID?,
)
