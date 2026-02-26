package pl.kacosmetology.scheduler.reservation.dto

import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.time.LocalDateTime

data class CreateReservationRequest(
    @field:NotNull
    @field:Positive(message = "ID pracownika musi być liczbą dodatnią")
    val employeeId: Long,

    @field:NotNull
    @field:Positive(message = "ID usługi musi być liczbą dodatnią")
    val serviceId: Long,

    @field:NotNull
    @field:Future(message = "Czas rezerwacji musi być w przyszłości")
    val startTime: LocalDateTime
)