package pl.kacosmetology.scheduler.scheduleblock.dto

import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

/** Changes only the interval of an existing company-scoped block. */
data class UpdateScheduleBlockRequest(
    @field:NotNull(message = "Czas rozpoczęcia jest wymagany") val startTime: LocalDateTime?,
    @field:NotNull(message = "Czas zakończenia jest wymagany") val endTime: LocalDateTime?,
)
