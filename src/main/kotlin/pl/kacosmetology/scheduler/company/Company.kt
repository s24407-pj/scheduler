package pl.kacosmetology.scheduler.company

import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.LocalTime

/** Represents a salon/company. Defines business hours and the slot interval used for availability calculation. */
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

    @Column(name = "max_no_shows", nullable = false)
    val maxNoShows: Int = 3,

    /** Percentage discount (0–100) applied to slots starting within [lastMinuteDiscountHours] from now. 0 disables the discount. */
    @Column(name = "last_minute_discount_percent", nullable = false)
    val lastMinuteDiscountPercent: Int = 0,

    /** Time window in hours within which the last-minute discount applies. */
    @Column(name = "last_minute_discount_hours", nullable = false)
    val lastMinuteDiscountHours: Int = 24,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: LocalDateTime? = null
)

/**
 * Returns the effective price for a slot, applying the last-minute discount if configured and applicable.
 * If [lastMinuteDiscountPercent] is 0, the base price is returned unchanged.
 */
fun Company.effectivePrice(basePrice: Int, slotStart: java.time.LocalDateTime): Int {
    if (lastMinuteDiscountPercent <= 0) return basePrice
    val cutoff = java.time.LocalDateTime.now().plusHours(lastMinuteDiscountHours.toLong())
    return if (slotStart.isBefore(cutoff))
        basePrice * (100 - lastMinuteDiscountPercent) / 100
    else basePrice
}
