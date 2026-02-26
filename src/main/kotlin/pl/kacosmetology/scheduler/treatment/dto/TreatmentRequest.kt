package pl.kacosmetology.scheduler.treatment.dto

import jakarta.validation.constraints.*

data class TreatmentRequest(
    @field:NotBlank(message = "Nazwa usługi nie może być pusta")
    @field:Size(min = 2, max = 100, message = "Nazwa musi mieć od 2 do 100 znaków")
    val name: String,

    @field:Min(value = 1, message = "Usługa musi trwać minimum 1 minutę")
    @field:Max(value = 480, message = "Usługa nie może trwać dłużej niż 8 godzin")
    val durationMinutes: Int,

    @field:PositiveOrZero(message = "Cena nie może być ujemna")
    val price: Int
)