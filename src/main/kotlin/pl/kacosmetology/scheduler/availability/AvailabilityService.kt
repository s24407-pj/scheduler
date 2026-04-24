package pl.kacosmetology.scheduler.availability

import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.company.effectivePrice
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlockRepository
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkScheduleRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Calculates available time slots for a given employee and offering on a specific date. */
@Service
class AvailabilityService(
    private val reservationRepository: ReservationRepository,
    private val offeringRepository: OfferingRepository,
    private val companyRepository: CompanyRepository,
    private val scheduleBlockRepository: ScheduleBlockRepository,
    private val workScheduleRepository: EmployeeWorkScheduleRepository,
    private val assignmentRepository: EmployeeOfferingAssignmentRepository
) {

    /**
     * Returns a list of available booking slots with pricing information.
     *
     * Uses the employee's work schedule for opening/closing hours (returns empty list if no entry for that day).
     * The slot interval is still taken from the company configuration.
     * Throws [IllegalArgumentException] if the employee has offering assignments and the requested offering is not among them.
     * Filters out slots that overlap with existing reservations, schedule blocks, and past times.
     * Applies the company's last-minute discount to slots starting within the configured time window.
     */
    fun getAvailableSlots(employeeId: Long, offeringId: Long, date: LocalDate): List<AvailableSlotResponse> {
        val offering = offeringRepository.findById(offeringId)
            .orElseThrow { IllegalArgumentException("Usługa nie istnieje") }

        val company = companyRepository.findById(offering.companyId)
            .orElseThrow { IllegalArgumentException("Firma nie istnieje") }

        if (assignmentRepository.existsByEmployeeId(employeeId) &&
            !assignmentRepository.existsByEmployeeIdAndOfferingId(employeeId, offeringId)
        ) {
            throw IllegalArgumentException("Ten pracownik nie wykonuje wybranej usługi")
        }

        val scheduleEntry = workScheduleRepository.findByEmployeeIdAndDayOfWeek(employeeId, date.dayOfWeek)
            ?: return emptyList()

        val requiredDuration = offering.durationMinutes.toLong()
        val openingTime = scheduleEntry.startTime
        val closingTime = scheduleEntry.endTime
        val slotStep = company.slotIntervalMinutes.toLong()

        val startOfDay = date.atStartOfDay()
        val endOfDay = date.plusDays(1).atStartOfDay()
        val existingReservations = reservationRepository.findByEmployeeIdAndDate(employeeId, startOfDay, endOfDay)
        val scheduleBlocks =
            scheduleBlockRepository.findByEmployeeIdAndStartTimeBetween(employeeId, startOfDay, endOfDay)

        val availableSlots = mutableListOf<AvailableSlotResponse>()
        var currentSlotStart = date.atTime(openingTime)
        val endOfWorkDay = date.atTime(closingTime)
        val earliestBookableTime =
            LocalDateTime.now(ZoneId.of("Europe/Warsaw")).plusMinutes(company.minBookingAdvanceMinutes.toLong())

        while (!currentSlotStart.plusMinutes(requiredDuration).isAfter(endOfWorkDay)) {
            val currentSlotEnd = currentSlotStart.plusMinutes(requiredDuration)

            val isOverlapping = existingReservations.any { reservation ->
                currentSlotStart.isBefore(reservation.endTime) && currentSlotEnd.isAfter(reservation.startTime)
            } || scheduleBlocks.any { block ->
                currentSlotStart.isBefore(block.endTime) && currentSlotEnd.isAfter(block.startTime)
            }

            if (!isOverlapping && currentSlotStart.isAfter(earliestBookableTime)) {
                val slotPrice = company.effectivePrice(offering.price, currentSlotStart)
                availableSlots.add(AvailableSlotResponse(currentSlotStart.toLocalTime(), slotPrice, offering.price))
            }

            currentSlotStart = currentSlotStart.plusMinutes(slotStep)
        }

        return availableSlots
    }
}
