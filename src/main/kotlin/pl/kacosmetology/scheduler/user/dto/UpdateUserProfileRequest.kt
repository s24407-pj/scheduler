package pl.kacosmetology.scheduler.user.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class UpdateUserProfileRequest(
    @field:NotBlank(message = "Imię nie może być puste")
    val firstName: String?,

    @field:NotBlank(message = "Nazwisko nie może być puste")
    val lastName: String?,

    @field:Email(message = "Niepoprawny format adresu e-mail")
    val email: String?
)