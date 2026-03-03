package pl.kacosmetology.scheduler.offering.dto

import pl.kacosmetology.scheduler.offering.OfferingCategory

/** Response body for an offering category. */
data class OfferingCategoryResponse(
    val id: Long,
    val name: String
)

/** Maps an [OfferingCategory] entity to an [OfferingCategoryResponse] DTO. */
fun OfferingCategory.toOfferingCategoryResponse() = OfferingCategoryResponse(id = id!!, name = name)
