package pl.kacosmetology.scheduler.scheduleblock.dto

import java.time.LocalDateTime
import java.util.UUID
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlock

/** Response DTO for a [ScheduleBlock]. */
data class ScheduleBlockResponse(
    val id: UUID,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val reason: String?,
    val createdAt: LocalDateTime?,
    val revision: Long = 0,
)

/** Maps a [ScheduleBlock] to its response DTO. */
fun ScheduleBlock.toResponse() =
    ScheduleBlockResponse(
        id = requireNotNull(id) { "Persisted schedule block must have an ID" },
        startTime = startTime,
        endTime = endTime,
        reason = reason,
        createdAt = createdAt,
        revision = version,
    )
