package pl.kacosmetology.scheduler.offering

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "offerings")
class Offering(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "company_id", nullable = false)
    val companyId: Long,

    @Column(nullable = false)
    val name: String,

    @Column(name = "duration_minutes", nullable = false)
    val durationMinutes: Int,

    @Column(nullable = false)
    val price: Int,

    @Column(nullable = false)
    val active: Boolean = true,

    @Column(name = "category_id")
    val categoryId: Long? = null,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: LocalDateTime? = null
)
