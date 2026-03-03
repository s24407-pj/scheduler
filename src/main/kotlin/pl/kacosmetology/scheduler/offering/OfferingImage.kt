package pl.kacosmetology.scheduler.offering

import jakarta.persistence.*
import java.time.OffsetDateTime

/** A single image associated with an [Offering], stored in Cloudflare R2. */
@Entity
@Table(name = "offering_images")
class OfferingImage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "offering_id", nullable = false)
    val offeringId: Long,

    @Column(name = "image_url", nullable = false)
    val imageUrl: String,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: OffsetDateTime? = null
)
