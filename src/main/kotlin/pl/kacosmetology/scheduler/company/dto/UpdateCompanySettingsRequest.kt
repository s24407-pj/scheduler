package pl.kacosmetology.scheduler.company.dto

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import java.time.LocalTime

/** Request body for updating company business hours and slot interval. */
data class UpdateCompanySettingsRequest(
    @field:NotNull(message = "Godzina otwarcia jest wymagana")
    val openingTime: LocalTime?,

    @field:NotNull(message = "Godzina zamknięcia jest wymagana")
    val closingTime: LocalTime?,

    @field:Min(value = 5, message = "Interwał slotów musi wynosić co najmniej 5 minut")
    @field:Max(value = 240, message = "Interwał slotów nie może przekraczać 240 minut")
    val slotIntervalMinutes: Int,

    @field:Min(value = 0, message = "Próg nieobecności nie może być ujemny")
    val maxNoShows: Int = 3
) {
    @AssertTrue(message = "Interwał slotów musi być wielokrotnością 5 minut")
    fun isSlotIntervalDivisibleByFive() = slotIntervalMinutes % 5 == 0
}
