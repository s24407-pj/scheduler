package pl.kacosmetology.scheduler.treatment.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Request body for creating a service category. */
data class CategoryRequest(
    @field:NotBlank(message = "Nazwa kategorii nie może być pusta")
    @field:Size(min = 2, max = 100, message = "Nazwa musi mieć od 2 do 100 znaków")
    val name: String
)
