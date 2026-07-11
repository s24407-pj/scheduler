package pl.kacosmetology.scheduler.reservation

import jakarta.persistence.*
import java.time.LocalDateTime

/** Lifecycle states supported by a reservation. */
enum class ReservationStatus { PENDING, CONFIRMED, CANCELLED, COMPLETED, NO_SHOW }

/** A priced booking connecting a customer, employee, and offering within one company. */
@Entity
@Table(name = "reservations")
class Reservation(
    id: Long? = null,

    @Column(name = "company_id", nullable = false)
    val companyId: Long,

    @Column(name = "customer_id", nullable = false)
    val customerId: Long,

    @Column(name = "employee_id", nullable = false)
    val employeeId: Long,

    @Column(name = "service_id", nullable = false)
    val serviceId: Long,

    @Column(nullable = false)
    val price: Int,

    @Column(name = "start_time", nullable = false)
    val startTime: LocalDateTime,

    @Column(name = "end_time", nullable = false)
    val endTime: LocalDateTime,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ReservationStatus = ReservationStatus.PENDING,

    @Version
    val version: Long = 0,

    @Column(name = "reminder_sent", nullable = false)
    var reminderSent: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: LocalDateTime? = null
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set
}
