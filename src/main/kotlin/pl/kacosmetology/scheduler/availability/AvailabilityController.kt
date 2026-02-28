package pl.kacosmetology.scheduler.availability

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalTime

/** REST API for querying available booking slots. Public — no authentication required. */
@RestController
@RequestMapping("/api/availability")
class AvailabilityController(
    private val availabilityService: AvailabilityService
) {

    /** Returns available time slots for a given employee and service on a specific date. */
    @GetMapping
    fun getAvailableSlots(
        @RequestParam employeeId: Long,
        @RequestParam serviceId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate
    ): List<LocalTime> {
        return availabilityService.getAvailableSlots(employeeId, serviceId, date)
    }
}