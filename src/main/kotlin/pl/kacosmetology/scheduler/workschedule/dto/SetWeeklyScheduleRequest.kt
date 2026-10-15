package pl.kacosmetology.scheduler.workschedule.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/** Request body for replacing an employee's weekly work schedule. Only an explicit empty list clears the schedule. */
data class SetWeeklyScheduleRequest(
    @field:NotNull(message = "Lista wpisów grafiku jest wymagana")
    @field:Size(max = 7, message = "Grafik nie może mieć więcej niż 7 wpisów")
    @field:Valid
    val entries: List<WorkScheduleEntryRequest>?,
)
