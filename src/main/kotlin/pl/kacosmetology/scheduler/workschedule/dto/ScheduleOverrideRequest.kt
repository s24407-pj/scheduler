package pl.kacosmetology.scheduler.workschedule.dto

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotNull
import java.time.LocalDate
import java.time.LocalTime
import pl.kacosmetology.scheduler.workschedule.EmployeeScheduleOverride

/** Replaces the weekly shift for one date; a day off must omit both times. */
data class ScheduleOverrideRequest(
    @field:NotNull(message = "Informacja o dniu wolnym jest wymagana") val dayOff: Boolean?,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
) {
    /** Enforces one continuous same-day replacement shift, or an explicit day off. */
    @AssertTrue(message = "Dzień wolny nie może zawierać godzin; zmiana wymaga godzin od wcześniejszej do późniejszej")
    fun isValidShift(): Boolean =
        when (dayOff) {
            true -> startTime == null && endTime == null
            false -> startTime != null && endTime != null && endTime.isAfter(startTime)
            null -> true
        }
}

/** Public schedule override representation. */
data class ScheduleOverrideResponse(
    val date: LocalDate,
    val dayOff: Boolean,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
)

/** Maps a persisted company-local override to its API representation. */
fun EmployeeScheduleOverride.toResponse() = ScheduleOverrideResponse(scheduleDate, dayOff, startTime, endTime)
