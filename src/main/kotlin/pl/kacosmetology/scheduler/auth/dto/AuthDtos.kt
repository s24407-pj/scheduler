package pl.kacosmetology.scheduler.auth.dto

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.*

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
    COMPANY_SELECTION_REQUIRED,
}

/** Staff login result; the HttpOnly cookie, never the response body, carries authentication. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class StaffLoginResponse(
    val status: StaffLoginStatus,
    val selectionToken: String? = null,
    val companies: List<StaffLoginCompany>? = null,
)

/** Email-first credentials remain independent for each company account. */
data class StaffEmailLoginRequest(
    @field:NotBlank(message = "Email jest wymagany")
    @field:Size(max = 255, message = "Email jest za długi")
    @field:Pattern(regexp = "^\\s*[^\\s@]+@[^\\s@]+\\.[^\\s@]+\\s*$", message = "Nieprawidłowy email")
    val email: String,
    @field:NotBlank(message = "Hasło jest wymagane")
    @field:Size(min = 8, max = 128, message = "Hasło musi mieć od 8 do 128 znaków")
    val password: String,
)

/** Only password-verified company names are returned to the browser. */
data class StaffLoginCompany(val companyId: UUID, val name: String)

/** A short-lived single-use capability, never a session credential. */
data class CompleteStaffLoginRequest(
    @field:NotBlank(message = "Wybór firmy jest wymagany")
    @field:Pattern(regexp = "^[A-Za-z0-9_-]{43}$", message = "Nieprawidłowy wybór firmy")
    val selectionToken: String,
    @field:NotNull(message = "Firma jest wymagana") val companyId: UUID,
)

/** Safe identity and exact authorization scope; the credential is never included. */
data class AuthenticatedSessionResponse(
    val userId: UUID,
    val username: String,
    val authorities: List<String>,
    val companyId: UUID?,
    val employmentId: UUID?,
)
