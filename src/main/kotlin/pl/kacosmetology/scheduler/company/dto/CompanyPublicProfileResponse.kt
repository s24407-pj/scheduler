package pl.kacosmetology.scheduler.company.dto

import java.time.LocalTime
import pl.kacosmetology.scheduler.company.Company

/** Public-facing company profile for customer booking pages. */
data class CompanyPublicProfileResponse(
    val id: Long,
    val name: String,
    val address: String?,
    val openingTime: LocalTime,
    val closingTime: LocalTime,
    val lastMinuteDiscountPercent: Int,
    val lastMinuteDiscountHours: Int,
)

/** Maps a [Company] entity to a [CompanyPublicProfileResponse] DTO. */
fun Company.toPublicProfileResponse() =
    CompanyPublicProfileResponse(
        id = requireNotNull(id) { "Persisted company must have an ID" },
        name = name,
        address = address,
        openingTime = openingTime,
        closingTime = closingTime,
        lastMinuteDiscountPercent = lastMinuteDiscountPercent,
        lastMinuteDiscountHours = lastMinuteDiscountHours,
    )
