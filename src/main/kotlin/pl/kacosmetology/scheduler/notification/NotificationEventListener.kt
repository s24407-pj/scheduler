package pl.kacosmetology.scheduler.notification

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.kacosmetology.scheduler.reservation.CustomerBlockedBookingsEvent
import pl.kacosmetology.scheduler.reservation.ReservationChangedEvent

/** Durable before-commit intent followed by best-effort after-commit dispatch; customer bookings emit no SMS event. */
@Component
class NotificationEventListener(
    private val outbox: NotificationOutbox,
    private val notificationService: NotificationService,
) {
    private val log = LoggerFactory.getLogger(NotificationEventListener::class.java)

    /** Persists an actual staff change atomically with its reservation revision. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun onReservationChanged(event: ReservationChangedEvent) = outbox.record(event)

    /** Persists a mandatory block-driven cancellation summary atomically with cancellations. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun onCustomerBlocked(event: CustomerBlockedBookingsEvent) = outbox.record(event)

    /** Nonblocking wake after commit; provider calls run on isolated SMS threads. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun afterReservationChanged(event: ReservationChangedEvent) = wake()

    /** Wakes delivery of a committed summary; durable retry state survives a worker failure. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun afterCustomerBlocked(event: CustomerBlockedBookingsEvent) = wake()

    private fun wake() {
        try {
            notificationService.dispatchDue()
        } catch (failure: Exception) {
            log.warn("Notification dispatch deferred: type={}", failure.javaClass.simpleName)
        }
    }
}
