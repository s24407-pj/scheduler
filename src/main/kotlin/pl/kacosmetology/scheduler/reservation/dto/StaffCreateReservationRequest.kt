package pl.kacosmetology.scheduler.reservation.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import java.time.LocalDateTime
import java.util.*
import pl.kacosmetology.scheduler.user.INTERNATIONAL_PHONE_PATTERN

/** Manual booking uses regular catalog terms unless staff explicitly override price or duration. */
data class StaffCreateReservationRequest(
    @field:NotNull(message = "ID pracownika jest wymagane") val employeeId: UUID?,
    @field:NotNull(message = "ID usługi jest wymagane") val serviceId: UUID?,
    @field:NotNull(message = "Czas rozpoczęcia jest wymagany") val startTime: LocalDateTime?,
    @field:Valid @field:NotNull(message = "Wybór klienta jest wymagany") val customer: StaffCustomerSelection?,
    @field:PositiveOrZero(message = "Cena nie może być ujemna") val price: Int? = null,
    @field:Min(value = 1, message = "Czas musi wynosić co najmniej minutę")
    @field:Max(value = 1440, message = "Czas nie może przekraczać 1440 minut")
    val durationMinutes: Int? = null,
    @field:Size(max = 500, message = "Notatka może mieć najwyżej 500 znaków") val internalNotes: String? = null,
    val overrideEmployeeBlock: Boolean = false,
    val notifyCustomer: Boolean = false,
)

/** The discriminator prevents phone-based resolution from replacing an explicitly selected customer. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = ExistingCustomerSelection::class, name = "existing"),
    JsonSubTypes.Type(value = NewCustomerSelection::class, name = "new"),
)
@Schema(oneOf = [ExistingCustomerSelection::class, NewCustomerSelection::class], discriminatorProperty = "kind")
sealed interface StaffCustomerSelection

@Schema(name = "ExistingCustomerSelection")
data class ExistingCustomerSelection(val customerId: UUID) : StaffCustomerSelection

@Schema(name = "NewCustomerSelection")
data class NewCustomerSelection(
    @field:Pattern(regexp = INTERNATIONAL_PHONE_PATTERN, message = "Podaj numer z prefiksem kraju, np. +48500123456")
    val phoneNumber: String,
    @field:NotBlank(message = "Imię klienta jest wymagane")
    @field:Size(max = 100, message = "Imię może mieć najwyżej 100 znaków")
    val firstName: String,
    @field:NotBlank(message = "Nazwisko klienta jest wymagane")
    @field:Size(max = 100, message = "Nazwisko może mieć najwyżej 100 znaków")
    val lastName: String,
    @field:Email(message = "Nieprawidłowy adres e-mail")
    @field:Size(max = 255, message = "Adres e-mail może mieć najwyżej 255 znaków")
    val contactEmail: String? = null,
) : StaffCustomerSelection
