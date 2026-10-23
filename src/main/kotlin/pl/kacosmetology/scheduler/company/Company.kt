package pl.kacosmetology.scheduler.company

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.*
import org.hibernate.annotations.UuidGenerator
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import pl.kacosmetology.scheduler.config.ErrorCode

/** Represents a salon/company. Defines business hours and the slot interval used for availability calculation. */
@Entity
@Table(name = "companies")
class Company(
    id: UUID? = null,
    @Column(nullable = false) var name: String,
    @Column(name = "tax_id") val taxId: String? = null,
    @Column(nullable = true) var address: String? = null,
    @Column(name = "opening_time", nullable = false) var openingTime: LocalTime = LocalTime.of(9, 0),
    @Column(name = "closing_time", nullable = false) var closingTime: LocalTime = LocalTime.of(17, 0),
    @Column(name = "slot_interval_minutes", nullable = false) var slotIntervalMinutes: Int = 30,
    @Column(name = "max_no_shows", nullable = false) var maxNoShows: Int = 3,

    /**
     * Percentage discount (0–100) applied to slots starting within [lastMinuteDiscountHours] from now. 0 disables the
     * discount.
     */
    @Column(name = "last_minute_discount_percent", nullable = false) var lastMinuteDiscountPercent: Int = 0,

    /** Time window in hours within which the last-minute discount applies. */
    @Column(name = "last_minute_discount_hours", nullable = false) var lastMinuteDiscountHours: Int = 24,

    /** Minimum number of minutes in advance a customer must book. 0 disables this restriction. */
    @Column(name = "min_booking_advance_minutes", nullable = false) var minBookingAdvanceMinutes: Int = 0,
    /** Maximum booking horizon measured by Warsaw calendar date, inclusive. */
    @Column(name = "max_booking_advance_days", nullable = false) var maxBookingAdvanceDays: Int = 90,
    /** Maximum future customer-created reservations; staff-created visits are exempt. */
    @Column(name = "max_future_customer_bookings", nullable = false) var maxFutureCustomerBookings: Int = 10,
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: LocalDateTime? = null,
) {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = id
        protected set
}

/** Applies an inclusive elapsed-time discount window and rounds whole złoty to nearest, ties to even. */
fun Company.effectivePrice(basePrice: Int, slotStart: LocalDateTime, now: Instant): Int {
    if (lastMinuteDiscountPercent <= 0) return basePrice
    val offsets = BUSINESS_ZONE.rules.getValidOffsets(slotStart)
    if (offsets.size != 1)
        throw ApiException(ErrorCode.INVALID_LOCAL_TIME, "Termin jest niejednoznaczny lub nie istnieje")
    val start = slotStart.toInstant(offsets.single())
    if (start.isBefore(now) || start.isAfter(now.plusSeconds(lastMinuteDiscountHours.toLong() * 3600))) return basePrice
    return BigDecimal.valueOf(basePrice.toLong())
        .multiply(BigDecimal.valueOf((100 - lastMinuteDiscountPercent).toLong()))
        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_EVEN)
        .intValueExact()
}

/** Compatibility overload for callers already holding an unambiguous Warsaw local business time. */
fun Company.effectivePrice(basePrice: Int, slotStart: LocalDateTime, now: LocalDateTime): Int {
    val offsets = BUSINESS_ZONE.rules.getValidOffsets(now)
    if (offsets.size != 1)
        throw ApiException(ErrorCode.INVALID_LOCAL_TIME, "Czas jest niejednoznaczny lub nie istnieje")
    return effectivePrice(basePrice, slotStart, now.toInstant(offsets.single()))
}
