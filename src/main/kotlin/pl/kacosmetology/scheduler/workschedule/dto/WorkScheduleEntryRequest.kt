package pl.kacosmetology.scheduler.workschedule.dto

import jakarta.validation.constraints.NotNull
import java.time.DayOfWeek
import java.time.LocalTime

/** A single day entry in a weekly work schedule request. */
data class WorkScheduleEntryRequest(
    @field:NotNull(message = "Dzień tygodnia jest wymagany")
    val dayOfWeek: DayOfWeek?,
    @field:NotNull(message = "Godzina rozpoczęcia jest wymagana")
    val startTime: LocalTime?,
    @field:NotNull(message = "Godzina zakończenia jest wymagana")
    val endTime: LocalTime?
)
