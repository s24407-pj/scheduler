package pl.kacosmetology.scheduler.scheduleblock.dto

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime
import java.util.UUID

/**
 * Request body for creating a new schedule block. Blocks may be ongoing or span multiple days; [endTime] must be
 * strictly after [startTime] — validated in the service layer.
 */
data class CreateScheduleBlockRequest(
    @field:NotNull(message = "Czas rozpoczęcia jest wymagany") val startTime: LocalDateTime?,
    @field:NotNull(message = "Czas zakończenia jest wymagany") val endTime: LocalDateTime?,
    @field:Size(max = 255, message = "Powód nie może być dłuższy niż 255 znaków") val reason: String? = null,

    /** OWNER may supply a target employee ID; EMPLOYEE may only specify their own identifier. */
    val employeeId: UUID? = null,
)
