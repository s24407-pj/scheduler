package pl.kacosmetology.scheduler.availability

import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.employeeservice.EmployeeServiceAssignmentRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlockRepository
import pl.kacosmetology.scheduler.treatment.TreatmentRepository
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkScheduleRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Calculates available time slots for a given employee and service on a specific date. */
@Service
class AvailabilityService(
    private val reservationRepository: ReservationRepository,
    private val serviceRepository: TreatmentRepository,
    private val companyRepository: CompanyRepository,
    private val scheduleBlockRepository: ScheduleBlockRepository,
    private val workScheduleRepository: EmployeeWorkScheduleRepository,
    private val assignmentRepository: EmployeeServiceAssignmentRepository
) {

    /**
     * Returns a list of available time slots for booking.
     *
     * Uses the employee's work schedule for opening/closing hours (returns empty list if no entry for that day).
     * The slot interval is still taken from the company configuration.
     * Throws [IllegalArgumentException] if the employee has service assignments and the requested service is not among them.
     * Filters out slots that overlap with existing reservations, schedule blocks, and past times.
     */
    fun getAvailableSlots(employeeId: Long, serviceId: Long, date: LocalDate): List<LocalTime> {
        val service = serviceRepository.findById(serviceId)
            .orElseThrow { IllegalArgumentException("Usługa nie istnieje") }

        val company = companyRepository.findById(service.companyId)
            .orElseThrow { IllegalArgumentException("Firma nie istnieje") }

        if (assignmentRepository.existsByEmployeeId(employeeId) &&
            !assignmentRepository.existsByEmployeeIdAndServiceId(employeeId, serviceId)
        ) {
            throw IllegalArgumentException("Ten pracownik nie wykonuje wybranej usługi")
        }

        val scheduleEntry = workScheduleRepository.findByEmployeeIdAndDayOfWeek(employeeId, date.dayOfWeek)
            ?: return emptyList()

        val requiredDuration = service.durationMinutes.toLong()
        val openingTime = scheduleEntry.startTime
        val closingTime = scheduleEntry.endTime
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
