package pl.kacosmetology.scheduler.availability

import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlockRepository
import java.time.LocalDateTime

/** Source of a time-range conflict in an employee's availability. */
enum class EmployeeAvailabilityConflictSource {
    RESERVATION,
    SCHEDULE_BLOCK
}

/** A busy time range that makes an employee unavailable. */
data class EmployeeAvailabilityConflict(
    val source: EmployeeAvailabilityConflictSource,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime
)

/** Shared policy for checking whether an employee is available in a given time range. */
@Service
class EmployeeAvailabilityPolicy(
    private val reservationRepository: ReservationRepository,
    private val scheduleBlockRepository: ScheduleBlockRepository
) {
    /** Returns all active reservations and schedule blocks overlapping [start, end). */
    fun findConflicts(employeeId: Long, start: LocalDateTime, end: LocalDateTime): List<EmployeeAvailabilityConflict> {
        val reservationConflicts = reservationRepository.findOverlapping(employeeId, start, end).map {
            EmployeeAvailabilityConflict(
                source = EmployeeAvailabilityConflictSource.RESERVATION,
                startTime = it.startTime,
                endTime = it.endTime
            )
        }
        val blockConflicts = scheduleBlockRepository.findOverlapping(employeeId, start, end).map {
            EmployeeAvailabilityConflict(
                source = EmployeeAvailabilityConflictSource.SCHEDULE_BLOCK,
                startTime = it.startTime,
                endTime = it.endTime
            )
        }

        return reservationConflicts + blockConflicts
    }

    /** Returns the first conflict for [start, end), preferring reservations over schedule blocks. */
    fun findFirstConflict(
        employeeId: Long,
        start: LocalDateTime,
        end: LocalDateTime
    ): EmployeeAvailabilityConflict? = findConflicts(employeeId, start, end).firstOrNull()

    /** Returns true when [start, end) overlaps any busy range in [conflicts]. */
    fun overlapsAny(
        start: LocalDateTime,
        end: LocalDateTime,
        conflicts: List<EmployeeAvailabilityConflict>
    ): Boolean = conflicts.any { start.isBefore(it.endTime) && end.isAfter(it.startTime) }

    /** Throws [IllegalStateException] when [start, end) is not available for [employeeId]. */
    fun assertAvailable(
        employeeId: Long,
        start: LocalDateTime,
        end: LocalDateTime,
        message: String = "Ten termin jest już zajęty"
    ) {
        if (findFirstConflict(employeeId, start, end) != null) {
            throw IllegalStateException(message)
        }
    }
}
