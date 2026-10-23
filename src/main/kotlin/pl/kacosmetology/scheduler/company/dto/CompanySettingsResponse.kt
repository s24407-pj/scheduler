package pl.kacosmetology.scheduler.company.dto

import java.time.LocalTime
import java.util.*
import pl.kacosmetology.scheduler.company.Company

/** Response body containing company business hour settings. */
data class CompanySettingsResponse(
    val id: UUID,
    val name: String,
    val openingTime: LocalTime,
    val closingTime: LocalTime,
    val slotIntervalMinutes: Int,
    val maxNoShows: Int,
    val lastMinuteDiscountPercent: Int,
    val lastMinuteDiscountHours: Int,
    val minBookingAdvanceMinutes: Int,
    val maxBookingAdvanceDays: Int,
    val maxFutureCustomerBookings: Int,
)

/** Maps a [Company] entity to a [CompanySettingsResponse] DTO. */
fun Company.toSettingsResponse() =
    CompanySettingsResponse(
        id = requireNotNull(id) { "Persisted company must have an ID" },
        name = name,
        openingTime = openingTime,
        closingTime = closingTime,
        slotIntervalMinutes = slotIntervalMinutes,
        maxNoShows = maxNoShows,
        lastMinuteDiscountPercent = lastMinuteDiscountPercent,
        lastMinuteDiscountHours = lastMinuteDiscountHours,
        minBookingAdvanceMinutes = minBookingAdvanceMinutes,
        maxBookingAdvanceDays = maxBookingAdvanceDays,
        maxFutureCustomerBookings = maxFutureCustomerBookings,
    )
