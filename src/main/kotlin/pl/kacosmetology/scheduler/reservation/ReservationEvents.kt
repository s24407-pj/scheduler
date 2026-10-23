package pl.kacosmetology.scheduler.reservation

import java.util.*

/** Published after a new reservation is successfully committed to the database. */
data class ReservationCreatedEvent(val reservation: Reservation)

/** Published after a reservation cancellation is successfully committed to the database. */
data class ReservationCancelledEvent(val reservation: Reservation)

/** Actual staff create/edit/restore/cancel; disabled notifications still invalidate obsolete delivery state. */
data class ReservationChangedEvent(val reservationId: UUID, val kind: String, val notifyCustomer: Boolean)

/** One mandatory summary for the future bookings cancelled by a single company block operation. */
data class CustomerBlockedBookingsEvent(val companyId: UUID, val customerId: UUID, val reservationIds: List<UUID>)
