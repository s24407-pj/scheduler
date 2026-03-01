package pl.kacosmetology.scheduler.treatment

import jakarta.persistence.*
import java.time.OffsetDateTime

/** A single image associated with a [ProvidedService], stored in Cloudflare R2. */
@Entity
@Table(name = "service_images")
class ServiceImage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "service_id", nullable = false)
    val serviceId: Long,

    @Column(name = "image_url", nullable = false)
    val imageUrl: String,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: OffsetDateTime? = null
)
