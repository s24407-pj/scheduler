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
 * REST API for managing company-scoped employee schedule blocks.
 * All endpoints require OWNER or EMPLOYEE role.
 */
@RestController
@RequestMapping("/api/schedule-blocks")
class ScheduleBlockController(
    private val scheduleBlockService: ScheduleBlockService
) {

    /**
     * Creates a non-overlapping schedule block for an employee of the authenticated company.
     * OWNER may specify a target employee via [CreateScheduleBlockRequest.employeeId];
     * EMPLOYEE always creates for themselves (JWT identity).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun createBlock(
        @Valid @RequestBody request: CreateScheduleBlockRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ScheduleBlockResponse {
        val requesterId = userDetails.id
        val companyId = userDetails.companyId
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisanej firmy")
        val isOwner = userDetails.authorities.any { it.authority == "ROLE_OWNER" }
        val targetEmployeeId = if (isOwner && request.employeeId != null) request.employeeId else requesterId

        return scheduleBlockService.createBlock(
            employeeId = targetEmployeeId,
            companyId = companyId,
            startTime = requireNotNull(request.startTime) { "Czas rozpoczęcia jest wymagany" },
            endTime = requireNotNull(request.endTime) { "Czas zakończenia jest wymagany" },
            reason = request.reason
        ).toResponse()
    }

    /**
     * Deletes a schedule block.
     * OWNER may delete any block within their company; EMPLOYEE may only delete their own blocks.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun deleteBlock(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) {
        val requesterId = userDetails.id
        val isOwner = userDetails.authorities.any { it.authority == "ROLE_OWNER" }
        val companyId = userDetails.requireCompanyId()
        scheduleBlockService.deleteBlock(id, requesterId, isOwner, companyId)
    }

    /**
     * Returns schedule blocks within a time range.
     * OWNER may supply [employeeId] to query another employee's blocks within their company.
     * EMPLOYEE always receives only their own blocks (the param is ignored).
     */
    @GetMapping("/employee")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun getMyBlocks(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) employeeId: Long?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) start: LocalDateTime,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) end: LocalDateTime
    ): List<ScheduleBlockResponse> {
        val requesterId = userDetails.id
        val companyId = userDetails.companyId
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisanej firmy")
        val isOwner = userDetails.authorities.any { it.authority == "ROLE_OWNER" }
        val targetId = if (isOwner && employeeId != null) employeeId else requesterId
        return scheduleBlockService.getEmployeeBlocks(companyId, targetId, start, end).map { it.toResponse() }
    }
}
