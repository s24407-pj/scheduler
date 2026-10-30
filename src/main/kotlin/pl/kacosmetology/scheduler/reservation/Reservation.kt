package pl.kacosmetology.scheduler.reservation

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.*
import org.hibernate.annotations.UuidGenerator

/** Lifecycle states supported by a reservation. */
@io.swagger.v3.oas.annotations.media.Schema(enumAsRef = true)
enum class ReservationStatus {
    BOOKED,
    CANCELLED,
    COMPLETED,
    NO_SHOW,
}

/** A priced booking connecting a customer, employee, and offering within one company. */
@Entity
@Table(name = "reservations")
class Reservation(
    id: UUID? = null,
    @Column(name = "company_id", nullable = false) val companyId: UUID,
    @Column(name = "customer_id", nullable = false) val customerId: UUID,
    @Column(name = "employee_id", nullable = false) var employeeId: UUID,
    @Column(name = "service_id", nullable = false) var serviceId: UUID,
    @Column(nullable = false) var price: Int,
    @Column(name = "start_time", nullable = false) var startTime: LocalDateTime,
    @Column(name = "end_time", nullable = false) var endTime: LocalDateTime,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: ReservationStatus = ReservationStatus.BOOKED,
    @Version val version: Long = 0,
    @Column(name = "accepted_service_name", nullable = false) var acceptedServiceName: String = "",
    @Column(name = "agreed_duration_minutes", nullable = false)
    var agreedDurationMinutes: Int = java.time.Duration.between(startTime, endTime).toMinutes().toInt(),
    @Column(name = "creation_source", nullable = false) val creationSource: String,
    @Column(nullable = false) var archived: Boolean = false,
    @Column(name = "internal_cancellation_reason") var internalCancellationReason: String? = null,
    @Column(name = "internal_notes") var internalNotes: String? = null,
    @Column(name = "customer_explanation") var customerExplanation: String? = null,
    @Column(name = "outcome_explanation") var outcomeExplanation: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    @org.hibernate.annotations.Generated(event = [org.hibernate.generator.EventType.INSERT])
    val createdAt: LocalDateTime? = null,
) {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = id
        protected set
}
