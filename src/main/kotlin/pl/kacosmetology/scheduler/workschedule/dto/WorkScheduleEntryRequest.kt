package pl.kacosmetology.scheduler.workschedule.dto

import java.time.DayOfWeek
import java.time.LocalTime

/** A single day entry in a weekly work schedule request. */
data class WorkScheduleEntryRequest(
    val dayOfWeek: DayOfWeek?,
    val startTime: LocalTime?,
    val endTime: LocalTime?
)
