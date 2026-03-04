package pl.kacosmetology.scheduler.availability

import java.time.LocalTime

/**
 * Represents a single available booking slot returned by the availability endpoint.
 *
 * @property time The start time of the slot.
 * @property price The effective price after applying any last-minute discount.
 * @property originalPrice The catalog price before any discount. Equal to [price] when no discount applies.
 */
data class AvailableSlotResponse(
    val time: LocalTime,
    val price: Int,
    val originalPrice: Int
)
