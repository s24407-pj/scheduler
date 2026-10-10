package pl.kacosmetology.scheduler.reservation

/**
 * Outcome of a booking request. [created] is false when an identical active reservation already existed and was
 * returned instead of creating a duplicate.
 */
data class BookingResult(val reservation: Reservation, val created: Boolean)
