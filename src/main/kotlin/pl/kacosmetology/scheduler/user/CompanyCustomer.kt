package pl.kacosmetology.scheduler.user

import jakarta.persistence.*
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** Company-owned phone identity. Staff visibility starts atomically with the first successful booking. */
@Entity
@Table(name = "company_customers")
class CompanyCustomer(
    id: UUID? = null,
    @Column(name = "company_id", nullable = false) val companyId: UUID,
    @Column(name = "phone_number", nullable = false) val phoneNumber: String,
    @Column(name = "first_name") var firstName: String? = null,
    @Column(name = "last_name") var lastName: String? = null,
    @Column(name = "contact_email") var contactEmail: String? = null,
    @Column(columnDefinition = "TEXT") var notes: String? = null,
    @Column(name = "first_booking_at") var firstBookingAt: Instant? = null,
    @Column(name = "created_at", insertable = false, updatable = false) val createdAt: OffsetDateTime? = null,
) {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = id
        protected set
}
