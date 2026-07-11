package pl.kacosmetology.scheduler.scheduleblock

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/** JPA repository for [ScheduleBlock] entities. */
@Repository
interface ScheduleBlockRepository : JpaRepository<ScheduleBlock, Long> {

    /**
     * Checks whether an employee has any schedule block in a company that overlaps the given time range.
     * Uses the standard half-open interval overlap condition: start < blockEnd AND end > blockStart.
     */
    @Query(
        """
        SELECT COUNT(b) > 0 FROM ScheduleBlock b
        WHERE b.companyId = :companyId
        AND b.employeeId = :employeeId
        AND (:start < b.endTime AND :end > b.startTime)
        """
    )
    fun existsOverlapping(companyId: Long, employeeId: Long, start: LocalDateTime, end: LocalDateTime): Boolean

    /**
     * Returns schedule blocks for an employee in a company that overlap the given time range.
     * Uses the standard half-open interval overlap condition: start < blockEnd AND end > blockStart.
     */
    @Query(
        """
        SELECT b FROM ScheduleBlock b
        WHERE b.companyId = :companyId
        AND b.employeeId = :employeeId
        AND (:start < b.endTime AND :end > b.startTime)
        ORDER BY b.startTime ASC
        """
    )
    fun findOverlapping(
        companyId: Long,
        employeeId: Long,
        start: LocalDateTime,
        end: LocalDateTime
    ): List<ScheduleBlock>
}
