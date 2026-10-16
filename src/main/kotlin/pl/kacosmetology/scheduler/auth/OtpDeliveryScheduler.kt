package pl.kacosmetology.scheduler.auth

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Single-instance startup recovery and retries for durable OTP delivery; tests invoke the service explicitly. */
@Component
@ConditionalOnProperty(name = ["scheduler.jobs.enabled"], havingValue = "true", matchIfMissing = true)
class OtpDeliveryScheduler(private val deliveries: OtpDeliveryService) {
    /** Marks unknown sends ambiguous before processing live pending OTP work after restart. */
    @EventListener(ApplicationReadyEvent::class)
    fun recover() {
        deliveries.recoverAfterRestart()
        deliveries.dispatchDue()
    }

    /** Retries definite temporary rejections only while the original Redis code remains live and unconsumed. */
    @Scheduled(fixedDelay = 30_000) fun retry() = deliveries.dispatchDue()
}
