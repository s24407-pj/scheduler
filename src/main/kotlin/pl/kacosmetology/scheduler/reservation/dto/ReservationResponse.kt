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

/** Dashboard DTO — includes both customerId and employeeId for the owner's cross-employee view. */
data class DashboardReservationResponse(
    val id: Long,
    val customerId: Long,
    val customerFirstName: String,
    val customerLastName: String,
    val employeeId: Long,
    val serviceId: Long,
    val price: Int,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val status: ReservationStatus,
    val createdAt: LocalDateTime?
)

/** Maps a [Reservation] to the customer-facing [ReservationResponse]. */
fun Reservation.toResponse() = ReservationResponse(
    id = requireNotNull(id) { "Reservation must be persisted before converting to DTO" },
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
    id = requireNotNull(id) { "Reservation must be persisted before converting to DTO" },
    customerId = customerId,
    serviceId = serviceId,
    price = price,
    startTime = startTime,
    endTime = endTime,
    status = status,
    createdAt = createdAt
)

/** Maps a [Reservation] to the owner dashboard [DashboardReservationResponse] with full employee+customer context. */
fun Reservation.toDashboardResponse(customerFirstName: String, customerLastName: String) = DashboardReservationResponse(
    id = requireNotNull(id) { "Reservation must be persisted before converting to DTO" },
    customerId = customerId,
    customerFirstName = customerFirstName,
    customerLastName = customerLastName,
    employeeId = employeeId,
    serviceId = serviceId,
    price = price,
    startTime = startTime,
    endTime = endTime,
    status = status,
    createdAt = createdAt
)
