package pl.kacosmetology.scheduler.user.dto

/** Response DTO with block/no-show status and company-scoped notes for a customer — visible to OWNER/EMPLOYEE. */
data class CustomerStatusResponse(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val phoneNumber: String,
    val noShowCount: Int,
    val blocked: Boolean,
    val notes: String? = null,
    val contactEmail: String? = null,
)

/** Company visit history visible to both staff roles; archived records stay outside ordinary lists. */
data class CustomerVisitResponse(
    val reservationId: Long,
    val employeeId: Long,
    val serviceId: Long,
    val acceptedServiceName: String,
    val price: Int,
    val start: java.time.LocalDateTime,
    val end: java.time.LocalDateTime,
    val status: pl.kacosmetology.scheduler.reservation.ReservationStatus,
    val createdAt: java.time.LocalDateTime?,
)
