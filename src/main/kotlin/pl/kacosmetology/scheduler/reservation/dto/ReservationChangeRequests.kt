package pl.kacosmetology.scheduler.reservation.dto

import jakarta.validation.constraints.*
import java.time.LocalDateTime

/** Direct staff edit preserves identity and commercial terms for an unchanged service. */
data class EditReservationRequest(
    @field:Positive(message = "ID pracownika musi być dodatnie") val employeeId: Long? = null,
    @field:Positive(message = "ID usługi musi być dodatnie") val serviceId: Long? = null,
    val startTime: LocalDateTime? = null,
    @field:PositiveOrZero(message = "Cena nie może być ujemna") val price: Int? = null,
    @field:Min(value = 1, message = "Czas musi wynosić co najmniej minutę")
    @field:Max(value = 1440, message = "Czas nie może przekraczać 1440 minut")
    val durationMinutes: Int? = null,
    val overrideEmployeeBlock: Boolean = false,
    val notifyCustomer: Boolean = false,
)

/** Revision-bound lifecycle command; private staff reasons are never returned to customers. */
data class ReservationActionRequest(
    @field:Size(max = 2000, message = "Powód jest za długi") val reason: String? = null,
    @field:Size(max = 2000, message = "Wyjaśnienie jest za długie") val customerExplanation: String? = null,
    val notifyCustomer: Boolean = false,
    val overrideEmployeeBlock: Boolean = false,
)
