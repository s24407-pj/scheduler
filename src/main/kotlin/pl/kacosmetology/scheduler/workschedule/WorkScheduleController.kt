package pl.kacosmetology.scheduler.workschedule

import jakarta.validation.Valid
import java.time.LocalDate
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.workschedule.dto.ScheduleOverrideRequest
import pl.kacosmetology.scheduler.workschedule.dto.ScheduleOverrideResponse
import pl.kacosmetology.scheduler.workschedule.dto.SetWeeklyScheduleRequest
import pl.kacosmetology.scheduler.workschedule.dto.WorkScheduleEntryResponse

/** REST API for managing employee weekly work schedules. OWNER and EMPLOYEE can read; only OWNER can update. */
@RestController
@RequestMapping("/api/employees")
class WorkScheduleController(
    private val workScheduleService: WorkScheduleService,
) {

    /** Returns the weekly schedule for the given employee. */
    @GetMapping("/{employeeId}/work-schedule")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun getSchedule(
        @PathVariable employeeId: UUID,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): List<WorkScheduleEntryResponse> = workScheduleService.getSchedule(userDetails.requireCompanyId(), employeeId)

    /**
     * Replaces the employee's weekly work schedule. Sending an empty entries list clears the schedule. Requires OWNER
     * role.
     */
    @PutMapping("/{employeeId}/work-schedule")
    @PreAuthorize("hasRole('OWNER')")
    fun setSchedule(
        @PathVariable employeeId: UUID,
        @Valid @RequestBody request: SetWeeklyScheduleRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): List<WorkScheduleEntryResponse> =
        workScheduleService.setSchedule(userDetails.requireCompanyId(), employeeId, request)

    /** Lists coworker schedule overrides in the authenticated company. */
    @GetMapping("/{employeeId}/schedule-overrides")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun getOverrides(
        @PathVariable employeeId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) start: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) end: LocalDate,
        @AuthenticationPrincipal principal: CustomUserDetails,
    ): List<ScheduleOverrideResponse> =
        workScheduleService.getOverrides(principal.requireCompanyId(), employeeId, start, end)

    /** Replaces one date's schedule; only owners manage scheduling. */
    @PutMapping("/{employeeId}/schedule-overrides/{date}")
    @PreAuthorize("hasRole('OWNER')")
    fun setOverride(
        @PathVariable employeeId: UUID,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @Valid @RequestBody request: ScheduleOverrideRequest,
        @AuthenticationPrincipal principal: CustomUserDetails,
    ): ScheduleOverrideResponse =
        workScheduleService.setOverride(principal.requireCompanyId(), employeeId, date, request)

    /** Restores the weekly schedule for one date. */
    @DeleteMapping("/{employeeId}/schedule-overrides/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun deleteOverride(
        @PathVariable employeeId: UUID,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @AuthenticationPrincipal principal: CustomUserDetails,
    ) = workScheduleService.deleteOverride(principal.requireCompanyId(), employeeId, date)
}
