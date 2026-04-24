package pl.kacosmetology.scheduler.notification

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.kacosmetology.scheduler.reservation.ReservationCancelledEvent
import pl.kacosmetology.scheduler.reservation.ReservationCreatedEvent

/** Sends SMS notifications after reservation transactions commit, keeping SMS calls outside DB transactions. */
@Component
class NotificationEventListener(private val notificationService: NotificationService) {

    private val logger = LoggerFactory.getLogger(NotificationEventListener::class.java)

    /** Sends a booking confirmation SMS after a new reservation is committed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onReservationCreated(event: ReservationCreatedEvent) {
        runCatching { notificationService.sendBookingConfirmation(event.reservation) }
            .onFailure {
                logger.warn(
                    "Failed to send booking confirmation for reservation ${event.reservation.id}",
                    it
                )
            }
    }

    /** Sends a cancellation SMS after a reservation cancellation is committed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onReservationCancelled(event: ReservationCancelledEvent) {
        runCatching { notificationService.sendCancellationNotification(event.reservation) }
            .onFailure {
                logger.warn(
                    "Failed to send cancellation notification for reservation ${event.reservation.id}",
                    it
                )
            }
    }
}
