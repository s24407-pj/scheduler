package pl.kacosmetology.scheduler.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

private const val PHONE_REGEXP = "^\\+?[0-9]{9,15}$"

data class RequestCodeRequest(
    @field:NotBlank(message = "Numer telefonu jest wymagany")
    @field:Pattern(regexp = PHONE_REGEXP, message = "Nieprawidłowy format numeru telefonu")
    val phoneNumber: String
)

data class VerifyCodeRequest(
    @field:NotBlank(message = "Numer telefonu jest wymagany")
    @field:Pattern(regexp = PHONE_REGEXP, message = "Nieprawidłowy format numeru telefonu")
    val phoneNumber: String,

    @field:NotBlank(message = "Kod SMS jest wymagany")
    @field:Size(min = 4, max = 6, message = "Kod SMS musi mieć 4-6 znaków")
    @field:Pattern(regexp = "^[0-9]+$", message = "Kod musi zawierać tylko cyfry")
    val code: String,

    @field:Size(max = 50, message = "Imię nie może być dłuższe niż 50 znaków")
    val firstName: String? = null,

    @field:Size(max = 50, message = "Nazwisko nie może być dłuższe niż 50 znaków")
    val lastName: String? = null
)

data class StaffLoginRequest(
    @field:NotBlank(message = "Email jest wymagany")
    @field:Email(message = "Nieprawidłowy format adresu email")
    val email: String,

    @field:NotBlank(message = "Hasło jest wymagane")
    @field:Size(min = 8, message = "Hasło musi mieć co najmniej 8 znaków")
    val password: String,

    @field:Positive(message = "Identyfikator zatrudnienia musi być dodatni")
    val employmentId: Long? = null
)

data class AuthResponse(
    val token: String
)

/** Possible outcomes of a staff login request. */
enum class StaffLoginStatus {
    AUTHENTICATED,
    EMPLOYMENT_SELECTION_REQUIRED
}

/** A company employment that can be selected for a staff session. */
data class StaffEmploymentOption(
    val employmentId: Long,
    val companyId: Long,
    val companyName: String,
    val role: String
)

/** Staff login result, either a scoped JWT or the employments available for selection. */
data class StaffLoginResponse(
    val status: StaffLoginStatus,
    val token: String?,
    val employments: List<StaffEmploymentOption>
)
