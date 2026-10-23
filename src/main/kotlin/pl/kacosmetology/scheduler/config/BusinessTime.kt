package pl.kacosmetology.scheduler.config

import java.time.LocalDateTime

/** Validates an unambiguous Warsaw interval that does not cross a daylight-saving transition. */
fun validateBusinessInterval(start: LocalDateTime, end: LocalDateTime) {
    val rules = BUSINESS_ZONE.rules
    val startOffsets = rules.getValidOffsets(start)
    val endOffsets = rules.getValidOffsets(end)
    if (startOffsets.size != 1 || endOffsets.size != 1 || !end.isAfter(start)) {
        throw ApiException(ErrorCode.INVALID_LOCAL_TIME, "Termin musi istnieć jednoznacznie w strefie Europe/Warsaw")
    }
    val startInstant = start.toInstant(startOffsets.single())
    val endInstant = end.toInstant(endOffsets.single())
    val transition = rules.nextTransition(startInstant)
    if (transition != null && transition.instant.isAfter(startInstant) && !transition.instant.isAfter(endInstant)) {
        throw ApiException(ErrorCode.INVALID_LOCAL_TIME, "Wizyta nie może obejmować zmiany czasu")
    }
}
