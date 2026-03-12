package pl.kacosmetology.scheduler.reservation

/** Published after a new reservation is successfully committed to the database. */
data class ReservationCreatedEvent(val reservation: Reservation)

/** Published after a reservation cancellation is successfully committed to the database. */
data class ReservationCancelledEvent(val reservation: Reservation)
