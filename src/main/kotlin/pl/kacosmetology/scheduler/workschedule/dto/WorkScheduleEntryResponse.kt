package pl.kacosmetology.scheduler.workschedule.dto

import java.time.DayOfWeek
import java.time.LocalTime
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkSchedule

/** Response body for a single day's work schedule entry. */
data class WorkScheduleEntryResponse(
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
)

/** Maps an [EmployeeWorkSchedule] entity to a [WorkScheduleEntryResponse] DTO. */
fun EmployeeWorkSchedule.toResponse() =
    WorkScheduleEntryResponse(
        dayOfWeek = dayOfWeek,
        startTime = startTime,
        endTime = endTime,
    )
