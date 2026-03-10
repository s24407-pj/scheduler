package pl.kacosmetology.scheduler.scheduleblock.dto

import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * Request body for creating a new schedule block.
 * [startTime] must be in the future; [endTime] must be strictly after [startTime] — validated in the service layer.
 */
data class CreateScheduleBlockRequest(
    @field:NotNull(message = "Czas rozpoczęcia jest wymagany")
    @field:Future(message = "Nie można dodać blokady w przeszłości")
    val startTime: LocalDateTime?,

    @field:NotNull(message = "Czas zakończenia jest wymagany")
    @field:Future(message = "Czas zakończenia musi być w przyszłości")
    val endTime: LocalDateTime?,

    @field:Size(max = 255, message = "Powód nie może być dłuższy niż 255 znaków")
    val reason: String? = null,

    /** OWNER may supply a target employee ID; EMPLOYEE field is ignored (JWT identity is used). */
    val employeeId: Long? = null
)
