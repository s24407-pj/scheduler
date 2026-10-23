package pl.kacosmetology.scheduler.reservation.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Size
import java.time.LocalDateTime
import java.util.*

/** Customer submission; a null employee requests atomic allocation among eligible staff. */
data class CreateReservationRequest(
    val employeeId: UUID? = null,
    val serviceId: UUID,
    val startTime: LocalDateTime,
    @field:Size(max = 100, message = "Imię nie może mieć więcej niż 100 znaków") val firstName: String? = null,
    @field:Size(max = 100, message = "Nazwisko nie może mieć więcej niż 100 znaków") val lastName: String? = null,
    @field:Email(message = "Nieprawidłowy adres e-mail")
    @field:Size(max = 255, message = "Adres e-mail jest za długi")
    val contactEmail: String? = null,
    val quoteId: UUID? = null,
)
