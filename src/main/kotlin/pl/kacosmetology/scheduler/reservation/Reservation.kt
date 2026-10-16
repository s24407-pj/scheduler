package pl.kacosmetology.scheduler.reservation

import jakarta.persistence.*
import java.time.LocalDateTime

/** Lifecycle states supported by a reservation. */
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
    id: Long? = null,
    @Column(name = "company_id", nullable = false) val companyId: Long,
    @Column(name = "customer_id", nullable = false) val customerId: Long,
    @Column(name = "employee_id", nullable = false) var employeeId: Long,
    @Column(name = "service_id", nullable = false) var serviceId: Long,
    @Column(nullable = false) var price: Int,
    @Column(name = "start_time", nullable = false) var startTime: LocalDateTime,
    @Column(name = "end_time", nullable = false) var endTime: LocalDateTime,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: ReservationStatus = ReservationStatus.BOOKED,
    @Version val version: Long = 0,
    @Column(name = "accepted_service_name", nullable = false) var acceptedServiceName: String = "",
    @Column(name = "service_name_source", nullable = false) var serviceNameSource: String = "ACCEPTED",
    @Column(name = "agreed_duration_minutes", nullable = false)
    var agreedDurationMinutes: Int = java.time.Duration.between(startTime, endTime).toMinutes().toInt(),
    @Column(name = "creation_source", nullable = false) val creationSource: String = "LEGACY",
    @Column(nullable = false) var archived: Boolean = false,
    @Column(name = "internal_cancellation_reason") var internalCancellationReason: String? = null,
    @Column(name = "customer_explanation") var customerExplanation: String? = null,
    @Column(name = "outcome_explanation") var outcomeExplanation: String? = null,
    @Column(name = "reminder_sent", nullable = false) var reminderSent: Boolean = false,
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    @org.hibernate.annotations.Generated(event = [org.hibernate.generator.EventType.INSERT])
    val createdAt: LocalDateTime? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set
}
