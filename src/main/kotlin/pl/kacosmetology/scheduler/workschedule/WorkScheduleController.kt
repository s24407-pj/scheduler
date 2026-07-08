package pl.kacosmetology.scheduler.workschedule

import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.workschedule.dto.SetWeeklyScheduleRequest
import pl.kacosmetology.scheduler.workschedule.dto.WorkScheduleEntryResponse

/**
 * REST API for managing employee weekly work schedules.
 * OWNER and EMPLOYEE can read; only OWNER can update.
 */
@RestController
@RequestMapping("/api/employees")
class WorkScheduleController(
    private val workScheduleService: WorkScheduleService
) {

    /** Returns the weekly schedule for the given employee. */
    @GetMapping("/{employeeId}/work-schedule")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun getSchedule(
        @PathVariable employeeId: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): List<WorkScheduleEntryResponse> = workScheduleService.getSchedule(userDetails.companyId!!, employeeId)

    /**
     * Replaces the employee's weekly work schedule.
     * Sending an empty entries list clears the schedule.
     * Requires OWNER role.
     */
    @PutMapping("/{employeeId}/work-schedule")
    @PreAuthorize("hasRole('OWNER')")
    fun setSchedule(
        @PathVariable employeeId: Long,
        @Valid @RequestBody request: SetWeeklyScheduleRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): List<WorkScheduleEntryResponse> =
        workScheduleService.setSchedule(userDetails.companyId!!, employeeId, request)
}
