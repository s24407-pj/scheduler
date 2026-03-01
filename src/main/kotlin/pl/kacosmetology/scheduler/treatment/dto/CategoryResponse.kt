package pl.kacosmetology.scheduler.treatment.dto

import pl.kacosmetology.scheduler.treatment.ServiceCategory

/** Response body for a service category. */
data class CategoryResponse(
    val id: Long,
    val name: String
)

/** Maps a [ServiceCategory] entity to a [CategoryResponse] DTO. */
fun ServiceCategory.toCategoryResponse() = CategoryResponse(id = id!!, name = name)
