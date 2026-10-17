package pl.kacosmetology.scheduler.offering

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** A single image associated with an [Offering], stored in Cloudflare R2. */
@Entity
@Table(name = "offering_images")
class OfferingImage(
    id: UUID? = null,
    @Column(name = "offering_id", nullable = false) val offeringId: UUID,
    @Column(name = "image_url", nullable = false) val imageUrl: String,
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: OffsetDateTime? = null,
) {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = id
        protected set
}
