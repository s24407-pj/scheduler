package pl.kacosmetology.scheduler.notification

import jakarta.persistence.EntityManager
import java.time.Clock
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.reservation.CustomerBlockedBookingsEvent
import pl.kacosmetology.scheduler.reservation.ReservationChangedEvent

/** Persists delivery intent in the booking transaction so rollback cannot leave an SMS eligible for dispatch. */
@Component
class NotificationOutbox(
    private val repository: NotificationDeliveryRepository,
    private val details: NotificationDetails,
    private val entityManager: EntityManager,
    private val clock: Clock,
) {
    /** Actual edits invalidate obsolete pending confirmations even if the new edit explicitly opts out of SMS. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(event: ReservationChangedEvent) {
        require(event.kind in setOf("CREATE", "EDIT", "RESTORE", "CANCEL"))
        entityManager.flush()
        val now = clock.instant()
        val reservation = details.reservation(event.reservationId) ?: return
        if (repository.hasStaffRevision(reservation.id, reservation.revision, event.kind)) return
        repository.invalidateStaff(event.reservationId, now)
        if (!event.notifyCustomer || !reservation.end.isAfter(now)) return
        if (event.kind == "CANCEL" && reservation.status != "CANCELLED") return
        if (event.kind != "CANCEL" && reservation.status != "BOOKED") return
        val id =
            repository.insert(
                event.kind,
                reservation,
                now,
                if (reservation.start.isAfter(now)) reservation.start else reservation.end,
            ) ?: return
        if (event.kind != "CANCEL") {
            repository.reminderEffect(reservation.id, reservation.date, reservation.revision)?.let {
                repository.state(id, it, now)
            }
        }
    }

    /** Records one mandatory block summary; membership is filtered again against current data at every attempt. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(event: CustomerBlockedBookingsEvent) {
        entityManager.flush()
        repository.insertSummary(event.companyId, event.customerId, event.reservationIds, clock.instant())
    }
}
