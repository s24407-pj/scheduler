package pl.kacosmetology.scheduler.reservation

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Repository
interface ReservationRepository : JpaRepository<Reservation, Long> {

    /** Checks if an employee has any active (non-cancelled, non-no-show) reservation overlapping the given time range. */
    @Query(
        """
        SELECT COUNT(r) > 0 FROM Reservation r
        WHERE r.employeeId = :employeeId
        AND r.status NOT IN ('CANCELLED', 'NO_SHOW')
        AND (:start < r.endTime AND :end > r.startTime)
    """
    )
    fun existsOverlapping(employeeId: Long, start: LocalDateTime, end: LocalDateTime): Boolean

    /** Returns active (non-cancelled, non-no-show) reservations overlapping the given time range. */
    @Query(
        """
        SELECT r FROM Reservation r
        WHERE r.employeeId = :employeeId
        AND r.status NOT IN ('CANCELLED', 'NO_SHOW')
        AND (:start < r.endTime AND :end > r.startTime)
        ORDER BY r.startTime ASC
    """
    )
    fun findOverlapping(employeeId: Long, start: LocalDateTime, end: LocalDateTime): List<Reservation>

    /** Returns all active (non-cancelled, non-no-show) reservations for an employee on a given day (used by availability check). */
    @Query(
        """
        SELECT r FROM Reservation r
        WHERE r.employeeId = :employeeId
        AND r.status NOT IN ('CANCELLED', 'NO_SHOW')
        AND r.startTime >= :startOfDay
        AND r.startTime < :endOfDay
        ORDER BY r.startTime ASC
    """
    )
    fun findByEmployeeIdAndDate(
        employeeId: Long,
        startOfDay: LocalDateTime,
        endOfDay: LocalDateTime
    ): List<Reservation>

    fun findAllByCustomerIdOrderByStartTimeDesc(customerId: Long): List<Reservation>

    /** Returns an employee's schedule overlapping a given time range (for the staff dashboard). */
    @Query(
        """
        SELECT r FROM Reservation r
        WHERE r.employeeId = :employeeId
        AND (:start < r.endTime AND :end > r.startTime)
        ORDER BY r.startTime ASC
    """
    )
    fun findEmployeeSchedule(employeeId: Long, start: LocalDateTime, end: LocalDateTime): List<Reservation>

    /** Returns reservations starting within [windowStart]..[windowEnd] that are active and have not had a reminder sent. */
    @Query(
        """
        SELECT r FROM Reservation r
        WHERE r.startTime BETWEEN :windowStart AND :windowEnd
        AND r.status IN ('PENDING', 'CONFIRMED')
        AND r.reminderSent = false
    """
    )
    fun findPendingReminders(
        @Param("windowStart") windowStart: LocalDateTime,
        @Param("windowEnd") windowEnd: LocalDateTime
    ): List<Reservation>

    /** Bulk-marks the given reservations as having had a reminder sent. */
    @Transactional
    @Modifying
    @Query("UPDATE Reservation r SET r.reminderSent = true WHERE r.id IN :ids")
    fun markRemindersAsSent(@Param("ids") ids: List<Long>)

    /**
     * Bulk-completes all PENDING/CONFIRMED reservations whose end time is at or before [now].
     * Returns the number of updated rows.
     */
    @Modifying
    @Transactional
    @Query(
        """
        UPDATE Reservation r SET r.status = 'COMPLETED'
        WHERE r.status IN ('PENDING', 'CONFIRMED')
        AND r.endTime <= :now
    """
    )
    fun autoCompleteElapsed(@Param("now") now: LocalDateTime): Int

    /** Checks if a customer has any reservation in the given company. */
    fun existsByCustomerIdAndCompanyId(customerId: Long, companyId: Long): Boolean

    /** Returns distinct customer IDs that have at least one reservation in the given company. */
    @Query("SELECT DISTINCT r.customerId FROM Reservation r WHERE r.companyId = :companyId")
    fun findDistinctCustomerIdsByCompanyId(@Param("companyId") companyId: Long): List<Long>

    /**
     * Returns reservations for a specific employee within a company that overlap a date range.
     * Used by the owner dashboard to display the calendar for any employee.
     */
    @Query(
        """
        SELECT r FROM Reservation r
        WHERE r.companyId = :companyId
        AND r.employeeId = :employeeId
        AND (:start < r.endTime AND :end > r.startTime)
        ORDER BY r.startTime ASC
    """
    )
    fun findByCompanyIdAndEmployeeIdAndDateRange(
        companyId: Long,
        employeeId: Long,
        start: LocalDateTime,
        end: LocalDateTime
    ): List<Reservation>
}
