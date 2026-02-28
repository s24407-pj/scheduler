package pl.kacosmetology.scheduler.reservation.dto

import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationStatus
import java.time.LocalDateTime

/** Customer-facing DTO — excludes internal fields like companyId, customerId and version. */
data class ReservationResponse(
    val id: Long,
    val employeeId: Long,
    val serviceId: Long,
    val price: Int,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val status: ReservationStatus,
    val createdAt: LocalDateTime?
)

/** Staff-facing DTO — includes customerId for the employee's schedule view. */
data class EmployeeReservationResponse(
    val id: Long,
    val customerId: Long,
    val serviceId: Long,
    val price: Int,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val status: ReservationStatus,
    val createdAt: LocalDateTime?
)

/** Maps a [Reservation] to the customer-facing [ReservationResponse]. */
fun Reservation.toResponse() = ReservationResponse(
    id = id!!,
    employeeId = employeeId,
    serviceId = serviceId,
    price = price,
    startTime = startTime,
    endTime = endTime,
    status = status,
    createdAt = createdAt
)

/** Maps a [Reservation] to the staff-facing [EmployeeReservationResponse]. */
fun Reservation.toEmployeeResponse() = EmployeeReservationResponse(
    id = id!!,
    customerId = customerId,
    serviceId = serviceId,
    price = price,
    startTime = startTime,
    endTime = endTime,
    status = status,
    createdAt = createdAt
)
