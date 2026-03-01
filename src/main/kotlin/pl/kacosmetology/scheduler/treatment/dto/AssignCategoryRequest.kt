package pl.kacosmetology.scheduler.treatment.dto

import jakarta.validation.constraints.Positive

/** Request body for assigning or removing a category from a service. A null [categoryId] removes the assignment. */
data class AssignCategoryRequest(
    @field:Positive(message = "ID kategorii musi być liczbą dodatnią")
    val categoryId: Long?
)
