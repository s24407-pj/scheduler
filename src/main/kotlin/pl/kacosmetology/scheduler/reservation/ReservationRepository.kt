package pl.kacosmetology.scheduler.reservation

import jakarta.persistence.LockModeType
import java.time.LocalDateTime
import java.util.*
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

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

    /** Loads all personal conflicts for a bounded availability range in one query. */
    @Query(
        "SELECT r FROM Reservation r WHERE r.companyId=:companyId AND r.customerId=:customerId AND r.status NOT IN ('CANCELLED','NO_SHOW') AND r.startTime<:end AND r.endTime>:start",
    )
    fun findCustomerOverlapping(
        companyId: UUID,
        customerId: UUID,
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<Reservation>

    fun findAllByCompanyIdAndCustomerIdAndArchivedFalseOrderByStartTimeDescIdDesc(
        companyId: UUID,
        customerId: UUID,
        pageable: Pageable,
    ): Slice<Reservation>

    fun findAllByCompanyIdAndCustomerIdAndArchivedFalseAndStatusAndStartTimeAfter(
        companyId: UUID,
        customerId: UUID,
        status: ReservationStatus,
        startTime: LocalDateTime,
    ): List<Reservation>

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

    fun findAllByCompanyIdAndCustomerIdAndArchivedFalseOrderByStartTimeDesc(
        companyId: UUID,
        customerId: UUID,
    ): List<Reservation>

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
