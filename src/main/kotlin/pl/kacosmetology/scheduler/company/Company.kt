package pl.kacosmetology.scheduler.company

import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.LocalTime

@Entity
@Table(name = "companies")
class Company(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val name: String,

    @Column(name = "tax_id")
    val taxId: String? = null,

    @Column(nullable = true)
    val address: String? = null,

    @Column(name = "opening_time", nullable = false)
    val openingTime: LocalTime = LocalTime.of(9, 0),

    @Column(name = "closing_time", nullable = false)
    val closingTime: LocalTime = LocalTime.of(17, 0),

    @Column(name = "slot_interval_minutes", nullable = false)
    val slotIntervalMinutes: Int = 30,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: LocalDateTime? = null
)
