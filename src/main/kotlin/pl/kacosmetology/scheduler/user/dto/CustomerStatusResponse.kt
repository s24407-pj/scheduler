package pl.kacosmetology.scheduler.user.dto

import java.util.*

/** Response DTO with block/no-show status and company-scoped notes for a customer — visible to OWNER/EMPLOYEE. */
data class CustomerStatusResponse(
    val id: UUID,
    val firstName: String,
    val lastName: String,
    val phoneNumber: String,
    val noShowCount: Int,
    val blocked: Boolean,
    val notes: String? = null,
    val contactEmail: String? = null,
    val revision: Long = 0,
)

/** Company visit history visible to both staff roles; archived records stay outside ordinary lists. */
data class CustomerVisitResponse(
    val reservationId: UUID,
    val employeeId: UUID,
    val serviceId: UUID,
    val acceptedServiceName: String,
    val price: Int,
    val start: java.time.LocalDateTime,
    val end: java.time.LocalDateTime,
    val status: pl.kacosmetology.scheduler.reservation.ReservationStatus,
    val createdAt: java.time.LocalDateTime?,
    val internalNotes: String? = null,
)
