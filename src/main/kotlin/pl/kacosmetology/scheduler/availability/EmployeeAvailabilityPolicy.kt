package pl.kacosmetology.scheduler.availability

import java.time.LocalDateTime
import java.util.UUID
import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlockRepository

/** Source of a time-range conflict in an employee's availability. */
enum class EmployeeAvailabilityConflictSource {
    RESERVATION,
    SCHEDULE_BLOCK,
}

/** A busy time range that makes an employee unavailable. */
data class EmployeeAvailabilityConflict(
    val source: EmployeeAvailabilityConflictSource,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
)

/** Shared policy for checking whether an employee is available in a given time range. */
@Service
class EmployeeAvailabilityPolicy(
    private val reservationRepository: ReservationRepository,
    private val scheduleBlockRepository: ScheduleBlockRepository,
) {
    /** Returns all active reservations and schedule blocks of [employeeId] at [companyId] overlapping [start, end). */
    fun findConflicts(
        companyId: UUID,
        employeeId: UUID,
        start: LocalDateTime,
        end: LocalDateTime,
        excludeBlockId: UUID? = null,
    ): List<EmployeeAvailabilityConflict> {
        val reservationConflicts =
            reservationRepository.findOverlapping(companyId, employeeId, start, end).map {
                EmployeeAvailabilityConflict(
                    source = EmployeeAvailabilityConflictSource.RESERVATION,
                    startTime = it.startTime,
                    endTime = it.endTime,
                )
            }
        val blockConflicts =
            scheduleBlockRepository.findOverlapping(companyId, employeeId, start, end, excludeBlockId).map {
                EmployeeAvailabilityConflict(
                    source = EmployeeAvailabilityConflictSource.SCHEDULE_BLOCK,
                    startTime = it.startTime,
                    endTime = it.endTime,
                )
            }

        return reservationConflicts + blockConflicts
    }

    /** Returns the first conflict for [start, end), preferring reservations over schedule blocks. */
    fun findFirstConflict(
        companyId: UUID,
        employeeId: UUID,
        start: LocalDateTime,
        end: LocalDateTime,
        excludeBlockId: UUID? = null,
    ): EmployeeAvailabilityConflict? = findConflicts(companyId, employeeId, start, end, excludeBlockId).firstOrNull()

    /** Returns true when [start, end) overlaps any busy range in [conflicts]. */
    fun overlapsAny(
        start: LocalDateTime,
        end: LocalDateTime,
        conflicts: List<EmployeeAvailabilityConflict>,
    ): Boolean = conflicts.any { start.isBefore(it.endTime) && end.isAfter(it.startTime) }

    /** Throws [ApiException] with [ErrorCode.SLOT_UNAVAILABLE] when [start, end) is not available for [employeeId]. */
    fun assertAvailable(
        companyId: UUID,
        employeeId: UUID,
        start: LocalDateTime,
        end: LocalDateTime,
        message: String = "Ten termin jest już zajęty",
    ) {
        if (findFirstConflict(companyId, employeeId, start, end) != null) {
            throw ApiException(ErrorCode.SLOT_UNAVAILABLE, message)
        }
    }
}
