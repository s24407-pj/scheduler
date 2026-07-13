package pl.kacosmetology.scheduler.scheduleblock.dto

import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlock
import java.time.LocalDateTime

/** Response DTO for a [ScheduleBlock]. */
data class ScheduleBlockResponse(
    val id: Long,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val reason: String?,
    val createdAt: LocalDateTime?
)

/** Maps a [ScheduleBlock] to its response DTO. */
fun ScheduleBlock.toResponse() = ScheduleBlockResponse(
    id = requireNotNull(id) { "Persisted schedule block must have an ID" },
    startTime = startTime,
    endTime = endTime,
    reason = reason,
    createdAt = createdAt
)
