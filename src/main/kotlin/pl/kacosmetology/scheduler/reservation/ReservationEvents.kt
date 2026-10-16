package pl.kacosmetology.scheduler.reservation

/** Published after a new reservation is successfully committed to the database. */
data class ReservationCreatedEvent(val reservation: Reservation)

/** Published after a reservation cancellation is successfully committed to the database. */
data class ReservationCancelledEvent(val reservation: Reservation)

/** Actual staff create/edit/restore/cancel; disabled notifications still invalidate obsolete delivery state. */
data class ReservationChangedEvent(val reservationId: Long, val kind: String, val notifyCustomer: Boolean)

/** One mandatory summary for the future bookings cancelled by a single company block operation. */
data class CustomerBlockedBookingsEvent(val companyId: Long, val customerId: Long, val reservationIds: List<Long>)
