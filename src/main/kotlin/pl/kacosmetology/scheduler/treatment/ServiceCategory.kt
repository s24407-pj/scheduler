package pl.kacosmetology.scheduler.treatment

import jakarta.persistence.*
import java.time.OffsetDateTime

/** Logical grouping for salon services. Scoped to a single company. */
@Entity
@Table(name = "service_categories")
class ServiceCategory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "company_id", nullable = false)
    val companyId: Long,

    @Column(nullable = false, length = 100)
    val name: String,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: OffsetDateTime? = null
)
