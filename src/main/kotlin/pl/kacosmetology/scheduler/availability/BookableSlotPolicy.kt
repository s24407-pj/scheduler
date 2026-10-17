package pl.kacosmetology.scheduler.availability

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import pl.kacosmetology.scheduler.config.validateBusinessInterval
import pl.kacosmetology.scheduler.workschedule.EmployeeScheduleOverrideRepository
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkScheduleRepository

/** One continuous effective shift; workload denominators use its full minutes, including blocks. */
data class EffectiveShift(val startTime: LocalTime, val endTime: LocalTime, val durationMinutes: Long)

/** Shared customer grid and effective schedule, including explicit date replacements and days off. */
@Service
class BookableSlotPolicy(
    private val workScheduleRepository: EmployeeWorkScheduleRepository,
    private val overrideRepository: EmployeeScheduleOverrideRepository,
) {
    /** A date override replaces the weekly schedule; an explicit day off overrides every weekly shift. */
    fun effectiveShift(companyId: UUID, employeeId: UUID, date: LocalDate): EffectiveShift? {
        overrideRepository.findByCompanyIdAndEmployeeIdAndScheduleDate(companyId, employeeId, date)?.let { override ->
            return if (override.dayOff) null
            else shift(date, requireNotNull(override.startTime), requireNotNull(override.endTime))
        }
        val weekly =
            workScheduleRepository.findByCompanyIdAndEmployeeIdAndDayOfWeek(companyId, employeeId, date.dayOfWeek)
                ?: return null
        return shift(date, weekly.startTime, weekly.endTime)
    }

    /** Grid begins at effective shift start, with the full duration fitting through the inclusive shift end. */
    fun slotStarts(
        companyId: UUID,
        employeeId: UUID,
        date: LocalDate,
        durationMinutes: Int,
        intervalMinutes: Int,
    ): List<LocalDateTime> {
        require(durationMinutes > 0 && intervalMinutes > 0) { "Czas trwania i interwał muszą być dodatnie" }
        val shift = effectiveShift(companyId, employeeId, date) ?: return emptyList()
        val dayEnd = date.atTime(shift.endTime)
        return generateSequence(date.atTime(shift.startTime)) { it.plusMinutes(intervalMinutes.toLong()) }
            .takeWhile { !it.plusMinutes(durationMinutes.toLong()).isAfter(dayEnd) }
            .filter { validInterval(it, it.plusMinutes(durationMinutes.toLong())) }
            .toList()
    }

    /** Accepts precisely the customer's effective shift grid, with valid unambiguous Warsaw time. */
    fun isOnGrid(
        companyId: UUID,
        employeeId: UUID,
        startTime: LocalDateTime,
        durationMinutes: Int,
        intervalMinutes: Int,
    ): Boolean {
        if (durationMinutes <= 0 || intervalMinutes <= 0) return false
        val date = startTime.toLocalDate()
        val shift = effectiveShift(companyId, employeeId, date) ?: return false
        val end = startTime.plusMinutes(durationMinutes.toLong())
        val offset = Duration.between(date.atTime(shift.startTime), startTime)
        return !offset.isNegative &&
            offset.toNanos() % Duration.ofMinutes(intervalMinutes.toLong()).toNanos() == 0L &&
            !end.isAfter(date.atTime(shift.endTime)) &&
            validInterval(startTime, end)
    }

    private fun shift(date: LocalDate, start: LocalTime, end: LocalTime): EffectiveShift =
        EffectiveShift(
            start,
            end,
            Duration.between(
                    date.atTime(start).atZone(BUSINESS_ZONE).toInstant(),
                    date.atTime(end).atZone(BUSINESS_ZONE).toInstant(),
                )
                .toMinutes(),
        )

    private fun validInterval(start: LocalDateTime, end: LocalDateTime): Boolean =
        try {
            validateBusinessInterval(start, end)
            true
        } catch (_: ApiException) {
            false
        }
}
