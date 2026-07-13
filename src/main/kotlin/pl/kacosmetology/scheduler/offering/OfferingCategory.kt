package pl.kacosmetology.scheduler.offering

import jakarta.persistence.*
import java.time.OffsetDateTime

/** Logical grouping for salon offerings. Scoped to a single company. */
@Entity
@Table(name = "offering_categories")
class OfferingCategory(
    id: Long? = null,

    @Column(name = "company_id", nullable = false)
    val companyId: Long,

    @Column(nullable = false, length = 100)
    val name: String,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: OffsetDateTime? = null
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set
}
