package pl.kacosmetology.scheduler.reservation

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Completes elapsed appointments within five minutes and catches up when the application restarts. */
@Component
@ConditionalOnProperty(name = ["scheduler.jobs.enabled"], havingValue = "true", matchIfMissing = true)
class ReservationCompletionScheduler(private val reservations: ReservationService) {
    /** Both startup and periodic execution use audited per-reservation changes. */
    @Scheduled(initialDelay = 1_000, fixedDelay = 60_000)
    fun completeElapsed() {
        reservations.autoCompleteElapsed()
    }
}
