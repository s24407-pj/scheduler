package pl.kacosmetology.scheduler.reservation

import jakarta.persistence.LockModeType
import java.time.LocalDateTime
import java.util.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface ReservationRepository : JpaRepository<Reservation, UUID> {
    /** Locks an existing company reservation for revision-checked lifecycle changes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id AND r.companyId = :companyId")
    fun findByIdAndCompanyIdForUpdate(id: UUID, companyId: UUID): Reservation?

    /** Customer conflicts are independent of employee allocation but scoped to one salon. */
    @Query(
        "SELECT COUNT(r)>0 FROM Reservation r WHERE r.companyId=:companyId AND r.customerId=:customerId AND r.status NOT IN ('CANCELLED','NO_SHOW') AND r.id<>:excludeId AND r.startTime<:end AND r.endTime>:start",
    )
    fun existsCustomerOverlapping(
        companyId: UUID,
        customerId: UUID,
        start: LocalDateTime,
        end: LocalDateTime,
        excludeId: UUID = UUID(0, 0),
    ): Boolean

    /** Customer-created future bookings count toward the salon's configurable cap. */
    @Query(
        "SELECT COUNT(r) FROM Reservation r WHERE r.companyId=:companyId AND r.customerId=:customerId AND r.creationSource='CUSTOMER' AND r.status='BOOKED' AND r.startTime>:now",
    )
    fun countFutureCustomerBookings(companyId: UUID, customerId: UUID, now: LocalDateTime): Long

    /** Elapsed appointments are completed individually so auditing and optimistic revisions are preserved. */
    @Query("SELECT r FROM Reservation r WHERE r.status='BOOKED' AND r.endTime<=:now ORDER BY r.companyId,r.id")
    fun findElapsedBookings(now: LocalDateTime): List<Reservation>

    /**
     * Checks if an employee has any active (non-cancelled, non-no-show) reservation at [companyId] overlapping the
     * given time range.
     */
    @Query(
        """
        SELECT COUNT(r) > 0 FROM Reservation r
        WHERE r.companyId = :companyId
        AND r.employeeId = :employeeId
        AND r.status NOT IN ('CANCELLED', 'NO_SHOW')
        AND (:start < r.endTime AND :end > r.startTime)
    """,
    )
    fun existsOverlapping(companyId: UUID, employeeId: UUID, start: LocalDateTime, end: LocalDateTime): Boolean

    /** Returns active (non-cancelled, non-no-show) reservations at [companyId] overlapping the given time range. */
    @Query(
        """
        SELECT r FROM Reservation r
        WHERE r.companyId = :companyId
        AND r.employeeId = :employeeId
        AND r.status NOT IN ('CANCELLED', 'NO_SHOW')
        AND (:start < r.endTime AND :end > r.startTime)
        ORDER BY r.startTime ASC
    """,
    )
    fun findOverlapping(companyId: UUID, employeeId: UUID, start: LocalDateTime, end: LocalDateTime): List<Reservation>

    /** Returns the active reservation exactly matching a booking request; used to make booking retries idempotent. */
    @Query(
        """
        SELECT r FROM Reservation r
        WHERE r.customerId = :customerId
        AND r.employeeId = :employeeId
        AND r.serviceId = :serviceId
        AND r.startTime = :startTime
        AND r.status NOT IN ('CANCELLED', 'NO_SHOW')
    """,
    )
    fun findActiveBooking(customerId: UUID, employeeId: UUID, serviceId: UUID, startTime: LocalDateTime): Reservation?

    fun findAllByCustomerIdAndArchivedFalseOrderByStartTimeDesc(customerId: UUID): List<Reservation>

    /** Returns an employee's schedule at [companyId] overlapping a given time range (for the staff dashboard). */
    @Query(
        """
        SELECT r FROM Reservation r
        WHERE r.companyId = :companyId
        AND r.employeeId = :employeeId
        AND r.archived = false
        AND (:start < r.endTime AND :end > r.startTime)
        ORDER BY r.startTime ASC
    """,
    )
    fun findEmployeeSchedule(
        companyId: UUID,
        employeeId: UUID,
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<Reservation>

    /**
     * Returns reservations starting within [windowStart]..[windowEnd] that are active and have not had a reminder sent.
     */
    @Query(
        """
        SELECT r FROM Reservation r
        WHERE r.startTime BETWEEN :windowStart AND :windowEnd
        AND r.status IN ('BOOKED')
        AND r.reminderSent = false
    """,
    )
    fun findPendingReminders(
        @Param("windowStart") windowStart: LocalDateTime,
        @Param("windowEnd") windowEnd: LocalDateTime,
    ): List<Reservation>

    /** Bulk-marks the given reservations as having had a reminder sent. */
    @Transactional
    @Modifying
    @Query("UPDATE Reservation r SET r.reminderSent = true WHERE r.id IN :ids")
    fun markRemindersAsSent(@Param("ids") ids: List<UUID>)

    /** Checks if a customer has any reservation in the given company. */
    fun existsByCustomerIdAndCompanyId(customerId: UUID, companyId: UUID): Boolean

    /** Returns distinct customer IDs that have at least one reservation in the given company. */
    @Query("SELECT DISTINCT r.customerId FROM Reservation r WHERE r.companyId = :companyId")
    fun findDistinctCustomerIdsByCompanyId(@Param("companyId") companyId: UUID): List<UUID>

    /**
     * Returns reservations for a specific employee within a company that overlap a date range. Used by the owner
     * dashboard to display the calendar for any employee.
     */
    @Query(
        """
        SELECT r FROM Reservation r
        WHERE r.companyId = :companyId
        AND r.employeeId = :employeeId
        AND r.archived = false
        AND (:start < r.endTime AND :end > r.startTime)
        ORDER BY r.startTime ASC
    """,
    )
    fun findByCompanyIdAndEmployeeIdAndDateRange(
        companyId: UUID,
        employeeId: UUID,
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<Reservation>
}
