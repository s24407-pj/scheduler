package pl.kacosmetology.scheduler.reservation.dto

import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

private const val PHONE_REGEXP = "^\\+?[0-9]{9,15}$"

/**
 * Request body for a staff member creating a reservation on behalf of a client.
 * If the client does not exist, [customerFirstName] and [customerLastName] are required to create the account.
 */
data class StaffCreateReservationRequest(
    @field:NotNull(message = "ID pracownika jest wymagane")
    @field:Positive(message = "ID pracownika musi być dodatnie")
    val employeeId: Long?,

    @field:NotNull(message = "ID usługi jest wymagane")
    @field:Positive(message = "ID usługi musi być dodatnie")
    val serviceId: Long?,

    @field:NotNull(message = "Czas rozpoczęcia jest wymagany")
    @field:Future(message = "Czas rezerwacji musi być w przyszłości")
    val startTime: LocalDateTime?,

    @field:NotBlank(message = "Numer telefonu klienta jest wymagany")
    @field:Pattern(regexp = PHONE_REGEXP, message = "Nieprawidłowy format numeru telefonu")
    val customerPhone: String?,

    @field:Size(max = 50, message = "Imię nie może być dłuższe niż 50 znaków")
    val customerFirstName: String? = null,

    @field:Size(max = 50, message = "Nazwisko nie może być dłuższe niż 50 znaków")
    val customerLastName: String? = null
)
