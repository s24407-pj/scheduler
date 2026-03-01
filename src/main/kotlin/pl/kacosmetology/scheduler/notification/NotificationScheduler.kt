package pl.kacosmetology.scheduler.notification

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import java.time.LocalDateTime

/** Periodically sends reminder SMS to customers for upcoming appointments. */
@Component
class NotificationScheduler(
    private val reservationRepository: ReservationRepository,
    private val notificationService: NotificationService
) {
    private val logger = LoggerFactory.getLogger(NotificationScheduler::class.java)

    /** Runs every hour on the hour. Sends reminders for reservations starting in 23–25 hours. */
    @Scheduled(cron = "0 0 * * * *")
    fun sendReminders() {
        val now = LocalDateTime.now()
        val pending = reservationRepository.findPendingReminders(
            windowStart = now.plusHours(23),
            windowEnd = now.plusHours(25)
        )
        pending.forEach { reservation ->
            runCatching { notificationService.sendReminder(reservation) }
                .onSuccess {
                    reservation.reminderSent = true
                    reservationRepository.save(reservation)
                }
                .onFailure { logger.warn("Failed to send reminder for reservation ${reservation.id}", it) }
        }
    }
}
