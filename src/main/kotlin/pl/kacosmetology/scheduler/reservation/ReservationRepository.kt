package pl.kacosmetology.scheduler.reservation

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface ReservationRepository : JpaRepository<Reservation, Long> {

    /** Checks if an employee has any non-cancelled reservation overlapping the given time range. */
    @Query(
        """
        SELECT COUNT(r) > 0 FROM Reservation r 
        WHERE r.employeeId = :employeeId 
        AND r.status != 'CANCELLED'
        AND (:start < r.endTime AND :end > r.startTime)
    """
    )
    fun existsOverlapping(employeeId: Long, start: LocalDateTime, end: LocalDateTime): Boolean

    /** Returns all non-cancelled reservations for an employee on a given day (used by availability check). */
    @Query(
        """
        SELECT r FROM Reservation r 
        WHERE r.employeeId = :employeeId 
        AND r.status != 'CANCELLED'
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

    /** Returns an employee's schedule within a given time range (for the staff dashboard). */
    @Query(
        """
        SELECT r FROM Reservation r 
        WHERE r.employeeId = :employeeId 
        AND r.startTime >= :start 
        AND r.startTime <= :end
        ORDER BY r.startTime ASC
    """
    )
    fun findEmployeeSchedule(employeeId: Long, start: LocalDateTime, end: LocalDateTime): List<Reservation>
}