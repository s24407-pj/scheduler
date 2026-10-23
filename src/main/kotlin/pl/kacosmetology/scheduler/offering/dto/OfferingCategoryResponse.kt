package pl.kacosmetology.scheduler.offering.dto

import java.util.*
import pl.kacosmetology.scheduler.offering.OfferingCategory

/** Response body for an offering category. */
data class OfferingCategoryResponse(
    val id: UUID,
    val name: String,
)

/** Maps an [OfferingCategory] entity to an [OfferingCategoryResponse] DTO. */
fun OfferingCategory.toOfferingCategoryResponse() =
    OfferingCategoryResponse(
        id = requireNotNull(id) { "Persisted offering category must have an ID" },
        name = name,
    )
