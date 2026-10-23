package pl.kacosmetology.scheduler.offering.dto

import java.util.*

/** Request body for assigning or removing a category from an offering. A null [categoryId] removes the assignment. */
data class AssignOfferingCategoryRequest(
    val categoryId: UUID?,
)
