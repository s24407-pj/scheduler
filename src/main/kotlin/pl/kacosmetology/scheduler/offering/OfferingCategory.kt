package pl.kacosmetology.scheduler.offering

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** Logical grouping for salon offerings. Scoped to a single company. */
@Entity
@Table(name = "offering_categories")
class OfferingCategory(
    id: UUID? = null,
    @Column(name = "company_id", nullable = false) val companyId: UUID,
    @Column(nullable = false, length = 100) val name: String,
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: OffsetDateTime? = null,
) {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = id
        protected set
}
