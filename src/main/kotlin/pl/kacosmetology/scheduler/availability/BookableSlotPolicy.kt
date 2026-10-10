package pl.kacosmetology.scheduler.availability

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkScheduleRepository

/**
 * Shared definition of the slot grid: which start times an employee offers at a company on a given day. Used by
 * availability (to list slots) and by customer booking (to accept only listed slots).
 */
@Service
class BookableSlotPolicy(
    private val workScheduleRepository: EmployeeWorkScheduleRepository,
) {
    /**
     * Returns candidate slot starts on [date] for [employeeId] at [companyId]: from the work-schedule start, every
     * [intervalMinutes], for slots of [durationMinutes] that end at or before the work-schedule end. Returns an empty
     * list when the employee has no work-schedule entry for that day. Does not check conflicts.
     */
    fun slotStarts(
        companyId: Long,
        employeeId: Long,
        date: LocalDate,
        durationMinutes: Int,
        intervalMinutes: Int,
    ): List<LocalDateTime> {
        val entry =
            workScheduleRepository.findByCompanyIdAndEmployeeIdAndDayOfWeek(
                companyId,
                employeeId,
                date.dayOfWeek,
            ) ?: return emptyList()
        val dayEnd = date.atTime(entry.endTime)
        return generateSequence(date.atTime(entry.startTime)) { it.plusMinutes(intervalMinutes.toLong()) }
            .takeWhile { !it.plusMinutes(durationMinutes.toLong()).isAfter(dayEnd) }
            .toList()
    }

    /** Returns true when [startTime] is one of [slotStarts] for its date. Does not check conflicts. */
    fun isOnGrid(
        companyId: Long,
        employeeId: Long,
        startTime: LocalDateTime,
        durationMinutes: Int,
        intervalMinutes: Int,
    ): Boolean {
        val date = startTime.toLocalDate()
        val entry =
            workScheduleRepository.findByCompanyIdAndEmployeeIdAndDayOfWeek(
                companyId,
                employeeId,
                date.dayOfWeek,
            ) ?: return false
        val offset = Duration.between(date.atTime(entry.startTime), startTime)
        val interval = Duration.ofMinutes(intervalMinutes.toLong())
        return !offset.isNegative &&
            offset.toNanos() % interval.toNanos() == 0L &&
            !startTime.plusMinutes(durationMinutes.toLong()).isAfter(date.atTime(entry.endTime))
    }
}
