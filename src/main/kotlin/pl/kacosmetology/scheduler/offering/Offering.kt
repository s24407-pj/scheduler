package pl.kacosmetology.scheduler.offering

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.*
import org.hibernate.annotations.UuidGenerator

/** Company-scoped salon service that can be booked by customers. */
@Entity
@Table(name = "offerings")
class Offering(
    id: UUID? = null,
    @Column(name = "company_id", nullable = false) val companyId: UUID,
    @Column(nullable = false) var name: String,
    @Column(name = "duration_minutes", nullable = false) var durationMinutes: Int,
    @Column(nullable = false) var price: Int,
    @Column(nullable = false) var active: Boolean = true,
    @Column(name = "category_id") var categoryId: UUID? = null,
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: LocalDateTime? = null,
) {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = id
        protected set
}
