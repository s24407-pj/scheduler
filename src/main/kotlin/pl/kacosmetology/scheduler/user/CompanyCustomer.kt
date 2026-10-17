package pl.kacosmetology.scheduler.user

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** Stores company-scoped business data (e.g. notes) about a customer. */
@Entity
@Table(name = "company_customers")
class CompanyCustomer(
    id: UUID? = null,
    @Column(name = "company_id", nullable = false) val companyId: UUID,
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(name = "first_name", nullable = false) var firstName: String = "",
    @Column(name = "last_name", nullable = false) var lastName: String = "",
    @Column(name = "contact_email") var contactEmail: String? = null,
    @Column(columnDefinition = "TEXT") var notes: String? = null,
    @Column(name = "created_at", insertable = false, updatable = false) val createdAt: OffsetDateTime? = null,
) {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = id
        protected set
}
