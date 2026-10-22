package pl.kacosmetology.scheduler.reservation.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.constraints.*
import java.time.LocalDateTime
import java.util.UUID

private const val UNSET_INTERNAL_NOTES = "\u0000__UNSET_INTERNAL_NOTES__"

/** Direct staff edit preserves identity and commercial terms for an unchanged service. */
data class EditReservationRequest(
    val employeeId: UUID? = null,
    val serviceId: UUID? = null,
    val startTime: LocalDateTime? = null,
    @field:PositiveOrZero(message = "Cena nie może być ujemna") val price: Int? = null,
    @field:Min(value = 1, message = "Czas musi wynosić co najmniej minutę")
    @field:Max(value = 1440, message = "Czas nie może przekraczać 1440 minut")
    val durationMinutes: Int? = null,
    @param:JsonSetter(nulls = Nulls.SET)
    @field:Size(max = 500, message = "Notatka może mieć najwyżej 500 znaków")
    val internalNotes: String? = UNSET_INTERNAL_NOTES,
    val overrideEmployeeBlock: Boolean = false,
    val notifyCustomer: Boolean = false,
) {
    @get:JsonIgnore
    val isInternalNotesPresent: Boolean
        get() = internalNotes !== UNSET_INTERNAL_NOTES

    @get:JsonIgnore
    val normalizedInternalNotes: String?
        get() = if (internalNotes === UNSET_INTERNAL_NOTES) null else internalNotes?.trim()?.ifBlank { null }
}

/** Revision-bound lifecycle command; private staff reasons are never returned to customers. */
data class ReservationActionRequest(
    @field:Size(max = 2000, message = "Powód jest za długi") val reason: String? = null,
    @field:Size(max = 2000, message = "Wyjaśnienie jest za długie") val customerExplanation: String? = null,
    val notifyCustomer: Boolean = false,
    val overrideEmployeeBlock: Boolean = false,
)
