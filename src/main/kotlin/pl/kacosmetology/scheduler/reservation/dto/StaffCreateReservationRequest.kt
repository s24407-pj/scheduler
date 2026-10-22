package pl.kacosmetology.scheduler.reservation.dto

import jakarta.validation.constraints.*
import java.time.LocalDateTime
import java.util.UUID

/** Manual booking uses regular catalog terms unless staff explicitly override price or duration. */
data class StaffCreateReservationRequest(
    @field:NotNull(message = "ID pracownika jest wymagane") val employeeId: UUID?,
    @field:NotNull(message = "ID usługi jest wymagane") val serviceId: UUID?,
    @field:NotNull(message = "Czas rozpoczęcia jest wymagany") val startTime: LocalDateTime?,
    @field:NotBlank(message = "Telefon klienta jest wymagany")
    @field:Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "Nieprawidłowy numer telefonu")
    val customerPhone: String?,
    @field:Size(max = 100, message = "Imię jest za długie") val customerFirstName: String? = null,
    @field:Size(max = 100, message = "Nazwisko jest za długie") val customerLastName: String? = null,
    @field:PositiveOrZero(message = "Cena nie może być ujemna") val price: Int? = null,
    @field:Min(value = 1, message = "Czas musi wynosić co najmniej minutę")
    @field:Max(value = 1440, message = "Czas nie może przekraczać 1440 minut")
    val durationMinutes: Int? = null,
    @field:Size(max = 2000, message = "Notatka może mieć najwyżej 2000 znaków") val internalNotes: String? = null,
    val overrideEmployeeBlock: Boolean = false,
    val notifyCustomer: Boolean = false,
)
