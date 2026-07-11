package pl.kacosmetology.scheduler.offering

import jakarta.persistence.*
import java.time.LocalDateTime

/** Company-scoped salon service that can be booked by customers. */
@Entity
@Table(name = "offerings")
class Offering(
    id: Long? = null,

    @Column(name = "company_id", nullable = false)
    val companyId: Long,

    @Column(nullable = false)
    var name: String,

    @Column(name = "duration_minutes", nullable = false)
    var durationMinutes: Int,

    @Column(nullable = false)
    var price: Int,

    @Column(nullable = false)
    var active: Boolean = true,

    @Column(name = "category_id")
    var categoryId: Long? = null,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: LocalDateTime? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set
}
