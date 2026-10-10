package pl.kacosmetology.scheduler.notification

import java.time.Clock
import java.time.LocalDateTime
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE_ID
import pl.kacosmetology.scheduler.reservation.ReservationRepository

/** Periodically sends reminder SMS to customers for upcoming appointments. */
@Component
class NotificationScheduler(
    private val reservationRepository: ReservationRepository,
    private val notificationService: NotificationService,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(NotificationScheduler::class.java)

    /** Runs every hour on the hour. Auto-completes all PENDING/CONFIRMED reservations whose end time has passed. */
    @Scheduled(cron = "0 0 * * * *", zone = BUSINESS_ZONE_ID)
    fun autoCompleteElapsedReservations() {
        val count = reservationRepository.autoCompleteElapsed(LocalDateTime.now(clock))
        if (count > 0) logger.info("Auto-completed $count elapsed reservations")
    }

    /** Runs every hour on the hour. Sends reminders for reservations starting in 23–25 hours. */
    @Scheduled(cron = "0 0 * * * *", zone = BUSINESS_ZONE_ID)
    fun sendReminders() {
        val now = LocalDateTime.now(clock)
        val pending =
            reservationRepository.findPendingReminders(
                windowStart = now.plusHours(23),
                windowEnd = now.plusHours(25),
            )
        val sentIds = mutableListOf<Long>()
        pending.forEach { reservation ->
            runCatching { notificationService.sendReminder(reservation) }
                .onSuccess {
                    sentIds += requireNotNull(reservation.id) { "Scheduled reservation must have an ID" }
                }
                .onFailure { logger.warn("Failed to send reminder for reservation ${reservation.id}", it) }
        }
        if (sentIds.isNotEmpty()) reservationRepository.markRemindersAsSent(sentIds)
    }
}
