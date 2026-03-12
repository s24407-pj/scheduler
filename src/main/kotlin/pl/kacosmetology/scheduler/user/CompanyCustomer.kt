package pl.kacosmetology.scheduler.user

import jakarta.persistence.*
import java.time.OffsetDateTime

/** Stores company-scoped business data (e.g. notes) about a customer. */
@Entity
@Table(name = "company_customers")
class CompanyCustomer(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "company_id", nullable = false)
    val companyId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(columnDefinition = "TEXT")
    var notes: String? = null,

    @Column(name = "created_at", insertable = false, updatable = false)
    val createdAt: OffsetDateTime? = null
)
