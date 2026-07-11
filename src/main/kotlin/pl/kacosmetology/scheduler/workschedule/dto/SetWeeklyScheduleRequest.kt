package pl.kacosmetology.scheduler.workschedule.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Size

/** Request body for replacing an employee's weekly work schedule. An empty or null list clears the schedule. */
data class SetWeeklyScheduleRequest(
    @field:Size(max = 7, message = "Grafik nie może mieć więcej niż 7 wpisów")
    @field:Valid
    val entries: List<WorkScheduleEntryRequest>?
)
