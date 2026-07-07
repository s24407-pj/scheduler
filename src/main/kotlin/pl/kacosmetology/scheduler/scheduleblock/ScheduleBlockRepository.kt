package pl.kacosmetology.scheduler.scheduleblock

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/** JPA repository for [ScheduleBlock] entities. */
@Repository
interface ScheduleBlockRepository : JpaRepository<ScheduleBlock, Long> {

    /** Returns all blocks for an employee that overlap [start, end). */
    @Query(
        """
        SELECT b FROM ScheduleBlock b
        WHERE b.employeeId = :employeeId
        AND (:start < b.endTime AND :end > b.startTime)
        ORDER BY b.startTime ASC
        """
    )
    fun findByEmployeeIdAndOverlappingRange(
        employeeId: Long,
        start: LocalDateTime,
        end: LocalDateTime
    ): List<ScheduleBlock>

    /**
     * Checks whether an employee has any schedule block that overlaps the given time range.
     * Uses the standard half-open interval overlap condition: start < blockEnd AND end > blockStart.
     */
    @Query(
        """
        SELECT COUNT(b) > 0 FROM ScheduleBlock b
        WHERE b.employeeId = :employeeId
        AND (:start < b.endTime AND :end > b.startTime)
        """
    )
    fun existsOverlapping(employeeId: Long, start: LocalDateTime, end: LocalDateTime): Boolean

    /**
     * Returns schedule blocks for an employee that overlap the given time range.
     * Uses the standard half-open interval overlap condition: start < blockEnd AND end > blockStart.
     */
    @Query(
        """
        SELECT b FROM ScheduleBlock b
        WHERE b.employeeId = :employeeId
        AND (:start < b.endTime AND :end > b.startTime)
        ORDER BY b.startTime ASC
        """
    )
    fun findOverlapping(employeeId: Long, start: LocalDateTime, end: LocalDateTime): List<ScheduleBlock>
}
