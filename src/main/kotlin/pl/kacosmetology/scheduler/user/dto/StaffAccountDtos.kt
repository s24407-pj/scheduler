package pl.kacosmetology.scheduler.user.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDateTime
import pl.kacosmetology.scheduler.company.CompanyEmployee

/** Owner-created staff identity; role is always EMPLOYEE. */
data class CreateEmployeeRequest(
    @field:NotBlank(message = "Imię jest wymagane")
    @field:Size(max = 100, message = "Imię jest za długie")
    val firstName: String,
    @field:NotBlank(message = "Nazwisko jest wymagane")
    @field:Size(max = 100, message = "Nazwisko jest za długie")
    val lastName: String,
    @field:NotBlank(message = "Email jest wymagany")
    @field:Size(max = 255, message = "Email jest za długi")
    @field:Pattern(regexp = "^\\s*[^\\s@]+@[^\\s@]+\\.[^\\s@]+\\s*$", message = "Nieprawidłowy email")
    val loginEmail: String,
    @field:Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "Nieprawidłowy numer telefonu")
    val contactPhone: String? = null,
)

/** Owner-controlled staff presentation and login email. */
data class UpdateEmployeeRequest(
    @field:NotBlank(message = "Imię jest wymagane")
    @field:Size(max = 100, message = "Imię jest za długie")
    val firstName: String,
    @field:NotBlank(message = "Nazwisko jest wymagane")
    @field:Size(max = 100, message = "Nazwisko jest za długie")
    val lastName: String,
    @field:NotBlank(message = "Email jest wymagany")
    @field:Size(max = 255, message = "Email jest za długi")
    @field:Pattern(regexp = "^\\s*[^\\s@]+@[^\\s@]+\\.[^\\s@]+\\s*$", message = "Nieprawidłowy email")
    val loginEmail: String,
    @field:Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "Nieprawidłowy numer telefonu")
    val contactPhone: String? = null,
)

/** Salon-specific customer profile editable by either staff role. */
data class UpdateCustomerProfileRequest(
    @field:NotBlank(message = "Imię jest wymagane")
    @field:Size(max = 100, message = "Imię jest za długie")
    val firstName: String,
    @field:NotBlank(message = "Nazwisko jest wymagane")
    @field:Size(max = 100, message = "Nazwisko jest za długie")
    val lastName: String,
    @field:Email(message = "Nieprawidłowy email")
    @field:Size(max = 255, message = "Email jest za długi")
    val contactEmail: String? = null,
)

/** Explicit staff access-state mutation. */
data class SetEmployeeEnabledRequest(val enabled: Boolean)

/** A company-owned staff profile without password or recovery secret. */
data class StaffProfileResponse(
    val id: Long,
    val accountId: Long,
    val companyId: Long,
    val firstName: String,
    val lastName: String,
    val loginEmail: String?,
    val contactPhone: String?,
    val photoUrl: String?,
    val role: String,
    val enabled: Boolean,
)

/** Maps only company-local staff presentation. */
fun CompanyEmployee.toStaffProfile() =
    StaffProfileResponse(
        userId,
        requireNotNull(id),
        companyId,
        firstName,
        lastName,
        loginEmail,
        contactPhone,
        photoUrl,
        role,
        enabled,
    )

/** One-use secret is returned to the owner for secure handoff; never included in ordinary profile reads. */
data class StaffPasswordLinkResponse(val token: String, val expiresAt: Instant)

/** Newly created employee and initial setup link. */
data class EmployeeCreatedResponse(val employee: StaffProfileResponse, val passwordSetup: StaffPasswordLinkResponse)

/** Existing future booking requiring owner attention when an employee is disabled. */
data class AffectedEmployeeBooking(val reservationId: Long, val start: LocalDateTime, val end: LocalDateTime)

/** Access-state change preserves bookings and returns affected future visits. */
data class EmployeeEnabledResponse(
    val employee: StaffProfileResponse,
    val affectedFutureBookings: List<AffectedEmployeeBooking>,
)

/** Current-password proof never authenticates a new session. */
data class ChangeStaffPasswordRequest(
    @field:NotBlank(message = "Aktualne hasło jest wymagane") val currentPassword: String,
    @field:Size(min = 8, max = 72, message = "Hasło musi mieć 8–72 znaki") val newPassword: String,
)

/** One-use setup/recovery token and selected password. */
data class SetStaffPasswordRequest(
    @field:NotBlank(message = "Token jest wymagany")
    @field:Size(max = 128, message = "Nieprawidłowy token")
    val token: String,
    @field:Size(min = 8, max = 72, message = "Hasło musi mieć 8–72 znaki") val newPassword: String,
)
