package pl.kacosmetology.scheduler.scheduleblock

import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import pl.kacosmetology.scheduler.scheduleblock.dto.CreateScheduleBlockRequest
import pl.kacosmetology.scheduler.scheduleblock.dto.ScheduleBlockResponse
import pl.kacosmetology.scheduler.scheduleblock.dto.toResponse
import pl.kacosmetology.scheduler.security.CustomUserDetails
import java.time.LocalDateTime

/**
 * REST API for managing an employee's schedule blocks.
 * All endpoints require OWNER or EMPLOYEE role.
 */
@RestController
@RequestMapping("/api/schedule-blocks")
class ScheduleBlockController(
    private val scheduleBlockService: ScheduleBlockService
) {

    /** Creates a new schedule block for the authenticated employee. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun createBlock(
        @Valid @RequestBody request: CreateScheduleBlockRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails?
    ): ScheduleBlockResponse {
        val employeeId = userDetails?.id
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Brak autoryzacji")
        val companyId = userDetails.companyId
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisanej firmy")

        return scheduleBlockService.createBlock(
            employeeId = employeeId,
            companyId = companyId,
            startTime = request.startTime!!,
            endTime = request.endTime!!,
            reason = request.reason
        ).toResponse()
    }

    /** Deletes a schedule block owned by the authenticated employee. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun deleteBlock(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails?
    ) {
        val employeeId = userDetails?.id
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Brak autoryzacji")
        scheduleBlockService.deleteBlock(id, employeeId)
    }

    /** Returns schedule blocks for the authenticated employee within a time range. */
    @GetMapping("/employee")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun getMyBlocks(
        @AuthenticationPrincipal userDetails: CustomUserDetails?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) start: LocalDateTime,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) end: LocalDateTime
    ): List<ScheduleBlockResponse> {
        val employeeId = userDetails?.id
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Brak autoryzacji")
        return scheduleBlockService.getEmployeeBlocks(employeeId, start, end).map { it.toResponse() }
    }
}
