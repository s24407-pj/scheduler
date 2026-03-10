package pl.kacosmetology.scheduler.user.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class UpdateUserProfileRequest(
    @field:NotBlank(message = "Imię nie może być puste")
    @field:Size(max = 50, message = "Imię nie może być dłuższe niż 50 znaków")
    val firstName: String?,

    @field:NotBlank(message = "Nazwisko nie może być puste")
    @field:Size(max = 50, message = "Nazwisko nie może być dłuższe niż 50 znaków")
    val lastName: String?,

    @field:Email(message = "Niepoprawny format adresu e-mail")
    @field:Size(max = 100, message = "Adres e-mail nie może być dłuższy niż 100 znaków")
    val email: String?
)