package pl.kacosmetology.scheduler.reservation.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.LocalDateTime
import java.util.*
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationStatus

/** Booking confirmation and replay expose the current reservation and accepted commercial agreement. */
data class ReservationResponse(
    val id: UUID,
    val employeeId: UUID,
    val serviceId: UUID,
    val price: Int,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val status: ReservationStatus,
    val createdAt: LocalDateTime?,
    val revision: Long = 0,
    val durationMinutes: Int = 0,
    val serviceName: String = "",
    val companyId: UUID,
    val companyName: String = "",
    val companyAddress: String? = null,
    val employeeName: String = "",
    val archived: Boolean = false,
    @field:JsonInclude(JsonInclude.Include.NON_NULL) val internalNotes: String? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL) val customerId: UUID? = null,
)

/** Staff-facing DTO — includes customerId for the employee's schedule view. */
data class EmployeeReservationResponse(
    val id: UUID,
    val customerId: UUID,
    val serviceId: UUID,
    val price: Int,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val status: ReservationStatus,
    val createdAt: LocalDateTime?,
    val revision: Long = 0,
    val internalCancellationReason: String? = null,
    val internalNotes: String? = null,
)

/** Dashboard DTO — includes both customerId and employeeId for the owner's cross-employee view. */
data class DashboardReservationResponse(
    val id: UUID,
    val customerId: UUID,
    val customerFirstName: String,
    val customerLastName: String,
    val employeeId: UUID,
    val serviceId: UUID,
    val price: Int,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val status: ReservationStatus,
    val createdAt: LocalDateTime?,
    val revision: Long = 0,
    val internalCancellationReason: String? = null,
    val internalNotes: String? = null,
)

/** Maps a [Reservation] to the customer-facing [ReservationResponse]. */
fun Reservation.toResponse() =
    ReservationResponse(
        id = requireNotNull(id) { "Reservation must be persisted before converting to DTO" },
        employeeId = employeeId,
        serviceId = serviceId,
        price = price,
        startTime = startTime,
        endTime = endTime,
        status = status,
        createdAt = createdAt,
        revision = version,
        durationMinutes = agreedDurationMinutes,
        serviceName = acceptedServiceName,
        companyId = companyId,
        archived = archived,
    )

/** Maps a [Reservation] to the staff-facing [EmployeeReservationResponse]. */
fun Reservation.toEmployeeResponse() =
    EmployeeReservationResponse(
        id = requireNotNull(id) { "Reservation must be persisted before converting to DTO" },
        customerId = customerId,
        serviceId = serviceId,
        price = price,
        startTime = startTime,
        endTime = endTime,
        status = status,
        createdAt = createdAt,
        revision = version,
        internalCancellationReason = internalCancellationReason,
        internalNotes = internalNotes,
    )

/** Maps a [Reservation] to the owner dashboard [DashboardReservationResponse] with full employee+customer context. */
fun Reservation.toDashboardResponse(customerFirstName: String, customerLastName: String) =
    DashboardReservationResponse(
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
        createdAt = createdAt,
        revision = version,
        internalCancellationReason = internalCancellationReason,
        internalNotes = internalNotes,
    )
