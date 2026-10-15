package pl.kacosmetology.scheduler.quote

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Durable commercial agreement for one customer, service and start; never reserves employee capacity. */
@Entity
@Table(name = "booking_quotes")
class BookingQuote(
    @Id val id: UUID,
    @Column(name = "customer_id", nullable = false) val customerId: Long,
    @Column(name = "company_id", nullable = false) val companyId: Long,
    @Column(name = "service_id", nullable = false) val serviceId: Long,
    @Column(name = "start_time", nullable = false) val startTime: Instant,
    @Column(name = "accepted_service_name", nullable = false) val acceptedServiceName: String,
    @Column(nullable = false) val price: Int,
    @Column(name = "duration_minutes", nullable = false) val durationMinutes: Int,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
)
