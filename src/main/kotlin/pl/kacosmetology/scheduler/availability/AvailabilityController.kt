package pl.kacosmetology.scheduler.availability

import java.time.LocalDate
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.kacosmetology.scheduler.security.CustomUserDetails

/** REST API for querying available booking slots. Public — no authentication required. */
@RestController
@RequestMapping("/api/availability")
class AvailabilityController(
    private val availabilityService: AvailabilityService,
) {

    /** Returns available time slots with pricing for a given employee and service on a specific date. */
    @GetMapping
    fun getAvailableSlots(
        @RequestParam employeeId: UUID,
        @RequestParam serviceId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @AuthenticationPrincipal principal: CustomUserDetails?,
    ): List<AvailableSlotResponse> {
        return availabilityService.getAvailableSlots(
            employeeId,
            serviceId,
            date,
            principal?.takeIf { it.authorities.any { authority -> authority.authority == "ROLE_CUSTOMER" } }?.id,
        )
    }
}
