package pl.kacosmetology.scheduler.company

import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Represents a salon/company. Defines business hours and the slot interval used for availability calculation. */
@Entity
@Table(name = "companies")
class Company(
    id: Long? = null,

    @Column(nullable = false)
    val name: String,

    @Column(name = "tax_id")
    val taxId: String? = null,

    @Column(nullable = true)
    val address: String? = null,

    @Column(name = "opening_time", nullable = false)
    var openingTime: LocalTime = LocalTime.of(9, 0),

    @Column(name = "closing_time", nullable = false)
    var closingTime: LocalTime = LocalTime.of(17, 0),

    @Column(name = "slot_interval_minutes", nullable = false)
    var slotIntervalMinutes: Int = 30,

    @Column(name = "max_no_shows", nullable = false)
    var maxNoShows: Int = 3,

    /** Percentage discount (0–100) applied to slots starting within [lastMinuteDiscountHours] from now. 0 disables the discount. */
    @Column(name = "last_minute_discount_percent", nullable = false)
    var lastMinuteDiscountPercent: Int = 0,

    /** Time window in hours within which the last-minute discount applies. */
    @Column(name = "last_minute_discount_hours", nullable = false)
    var lastMinuteDiscountHours: Int = 24,

    /** Minimum number of minutes in advance a customer must book. 0 disables this restriction. */
    @Column(name = "min_booking_advance_minutes", nullable = false)
    var minBookingAdvanceMinutes: Int = 0,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: LocalDateTime? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set
}

/**
 * Returns the effective price for a slot, applying the last-minute discount if configured and applicable.
 * If [lastMinuteDiscountPercent] is 0, the base price is returned unchanged.
 */
fun Company.effectivePrice(
    basePrice: Int,
    slotStart: LocalDateTime,
    now: LocalDateTime = LocalDateTime.now(ZoneId.of("Europe/Warsaw"))
): Int {
    if (lastMinuteDiscountPercent <= 0) return basePrice
    val cutoff = now.plusHours(lastMinuteDiscountHours.toLong())
    return if (slotStart.isBefore(cutoff))
        basePrice * (100 - lastMinuteDiscountPercent) / 100
    else basePrice
}
