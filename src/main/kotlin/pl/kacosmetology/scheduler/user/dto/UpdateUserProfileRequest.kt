package pl.kacosmetology.scheduler.user.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class UpdateUserProfileRequest(
    @get:NotBlank(message = "Imię nie może być puste")
    val firstName: String?,

    @get:NotBlank(message = "Nazwisko nie może być puste")
    val lastName: String?,

    @get:Email(message = "Niepoprawny format adresu e-mail")
    val email: String?
)