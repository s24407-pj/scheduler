package pl.kacosmetology.scheduler.company.dto

import pl.kacosmetology.scheduler.company.Company
import java.time.LocalTime

/** Response body containing company business hour settings. */
data class CompanySettingsResponse(
    val id: Long,
    val name: String,
    val openingTime: LocalTime,
    val closingTime: LocalTime,
    val slotIntervalMinutes: Int,
    val maxNoShows: Int,
    val lastMinuteDiscountPercent: Int,
    val lastMinuteDiscountHours: Int,
    val minBookingAdvanceMinutes: Int
)

/** Maps a [Company] entity to a [CompanySettingsResponse] DTO. */
fun Company.toSettingsResponse() = CompanySettingsResponse(
    id = id!!,
    name = name,
    openingTime = openingTime,
    closingTime = closingTime,
    slotIntervalMinutes = slotIntervalMinutes,
    maxNoShows = maxNoShows,
    lastMinuteDiscountPercent = lastMinuteDiscountPercent,
    lastMinuteDiscountHours = lastMinuteDiscountHours,
    minBookingAdvanceMinutes = minBookingAdvanceMinutes
)
