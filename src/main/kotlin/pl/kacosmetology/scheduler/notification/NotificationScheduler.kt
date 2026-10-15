package pl.kacosmetology.scheduler.notification

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE_ID

/** Single-instance trigger: completion has its own reservation scheduler; missed reminder runs are not recovered. */
@Component
@ConditionalOnProperty(name = ["scheduler.jobs.enabled"], havingValue = "true", matchIfMissing = true)
class NotificationScheduler(private val notificationService: NotificationService) {
    /** Marks unknown provider outcomes conservatively at startup without recovering a missed reminder run. */
    @EventListener(ApplicationReadyEvent::class) fun recoverDeliveryState() = notificationService.recoverAfterRestart()

    /** Begins the previous-calendar-day reminder run exactly at 09:00 Warsaw, with a fixed 09:05 deadline. */
    @Scheduled(cron = "0 0 9 * * *", zone = BUSINESS_ZONE_ID)
    fun sendReminders() = notificationService.startReminderRun()

    /** Retries durable staff/summary work and only the currently uninterrupted reminder run. */
    @Scheduled(fixedDelay = 30_000) fun retryDeliveries() = notificationService.dispatchDue()
}
