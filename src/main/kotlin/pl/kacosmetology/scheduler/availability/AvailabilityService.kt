package pl.kacosmetology.scheduler.availability

import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlockRepository
import pl.kacosmetology.scheduler.treatment.TreatmentRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Calculates available time slots for a given employee and service on a specific date. */
@Service
class AvailabilityService(
    private val reservationRepository: ReservationRepository,
    private val serviceRepository: TreatmentRepository,
    private val companyRepository: CompanyRepository,
    private val scheduleBlockRepository: ScheduleBlockRepository
) {

    /**
     * Returns a list of available time slots for booking.
     *
     * Reads opening hours and slot interval from the company configuration stored in the database.
     * Filters out slots that overlap with existing reservations, schedule blocks, and past times.
     */
    fun getAvailableSlots(employeeId: Long, serviceId: Long, date: LocalDate): List<LocalTime> {
        val service = serviceRepository.findById(serviceId)
            .orElseThrow { IllegalArgumentException("Usługa nie istnieje") }

        val company = companyRepository.findById(service.companyId)
            .orElseThrow { IllegalArgumentException("Firma nie istnieje") }

        val requiredDuration = service.durationMinutes.toLong()
        val openingTime = company.openingTime
        val closingTime = company.closingTime
        val slotStep = company.slotIntervalMinutes.toLong()

        val startOfDay = date.atStartOfDay()
        val endOfDay = date.plusDays(1).atStartOfDay()
        val existingReservations = reservationRepository.findByEmployeeIdAndDate(employeeId, startOfDay, endOfDay)
        val scheduleBlocks = scheduleBlockRepository.findByEmployeeIdAndStartTimeBetween(employeeId, startOfDay, endOfDay)

        val availableSlots = mutableListOf<LocalTime>()
        var currentSlotStart = date.atTime(openingTime)
        val endOfWorkDay = date.atTime(closingTime)

        while (!currentSlotStart.plusMinutes(requiredDuration).isAfter(endOfWorkDay)) {
            val currentSlotEnd = currentSlotStart.plusMinutes(requiredDuration)

            val isOverlapping = existingReservations.any { reservation ->
                currentSlotStart.isBefore(reservation.endTime) && currentSlotEnd.isAfter(reservation.startTime)
            } || scheduleBlocks.any { block ->
                currentSlotStart.isBefore(block.endTime) && currentSlotEnd.isAfter(block.startTime)
            }

            if (!isOverlapping && currentSlotStart.isAfter(LocalDateTime.now())) {
                availableSlots.add(currentSlotStart.toLocalTime())
            }

            currentSlotStart = currentSlotStart.plusMinutes(slotStep)
        }

        return availableSlots
    }
}