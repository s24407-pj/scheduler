package pl.kacosmetology.scheduler.scheduleblock

import java.time.LocalDateTime
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/** JPA repository for [ScheduleBlock] entities. */
@Repository
interface ScheduleBlockRepository : JpaRepository<ScheduleBlock, UUID> {
    /** Loads only a block belonging to the authenticated tenant. */
    fun findByIdAndCompanyId(id: UUID, companyId: UUID): ScheduleBlock?

    /**
     * Checks whether an employee has any schedule block in a company that overlaps the given time range. Uses the
     * standard half-open interval overlap condition: start < blockEnd AND end > blockStart.
     */
    @Query(
        """
        SELECT COUNT(b) > 0 FROM ScheduleBlock b
        WHERE b.companyId = :companyId
        AND b.employeeId = :employeeId
        AND (:start < b.endTime AND :end > b.startTime)
        """,
    )
    fun existsOverlapping(companyId: UUID, employeeId: UUID, start: LocalDateTime, end: LocalDateTime): Boolean

    /**
     * Returns schedule blocks for an employee in a company that overlap the given time range. Uses the standard
     * half-open interval overlap condition: start < blockEnd AND end > blockStart. Excludes [excludeBlockId] in the
     * query when supplied, so an edited block does not conflict with itself.
     */
    @Query(
        """
        SELECT b FROM ScheduleBlock b
        WHERE b.companyId = :companyId
        AND b.employeeId = :employeeId
        AND (:start < b.endTime AND :end > b.startTime)
        AND (:excludeBlockId IS NULL OR b.id <> :excludeBlockId)
        ORDER BY b.startTime ASC
        """,
    )
    fun findOverlapping(
        companyId: UUID,
        employeeId: UUID,
        start: LocalDateTime,
        end: LocalDateTime,
        excludeBlockId: UUID? = null,
    ): List<ScheduleBlock>
}
