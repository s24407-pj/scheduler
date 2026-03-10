package pl.kacosmetology.scheduler.offering.dto

import jakarta.validation.constraints.Positive

/** Request body for assigning or removing a category from an offering. A null [categoryId] removes the assignment. */
data class AssignOfferingCategoryRequest(
    @field:Positive(message = "ID kategorii musi być liczbą dodatnią")
    val categoryId: Long?
)
