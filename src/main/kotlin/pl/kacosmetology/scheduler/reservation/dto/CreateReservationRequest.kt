package pl.kacosmetology.scheduler.reservation.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime
import java.util.*

/** Customer submission; a null employee requests atomic allocation among eligible staff. */
data class CreateReservationRequest(
    val employeeId: UUID? = null,
    val serviceId: UUID,
    val startTime: LocalDateTime,
    @field:Valid val firstVisitDetails: FirstVisitDetails? = null,
    val quoteId: UUID? = null,
)

/** First successful booking establishes the salon profile; these fields never update an established profile. */
data class FirstVisitDetails(
    @field:NotBlank(message = "Imię klienta jest wymagane")
    @field:Size(max = 100, message = "Imię może mieć najwyżej 100 znaków")
    val firstName: String,
    @field:NotBlank(message = "Nazwisko klienta jest wymagane")
    @field:Size(max = 100, message = "Nazwisko może mieć najwyżej 100 znaków")
    val lastName: String,
    @field:Email(message = "Nieprawidłowy adres e-mail")
    @field:Size(max = 255, message = "Adres e-mail może mieć najwyżej 255 znaków")
    val contactEmail: String? = null,
)
