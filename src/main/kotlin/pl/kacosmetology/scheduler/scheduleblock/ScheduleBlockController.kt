package pl.kacosmetology.scheduler.scheduleblock

import jakarta.validation.Valid
import java.time.LocalDateTime
import java.util.*
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.scheduleblock.dto.CreateScheduleBlockRequest
import pl.kacosmetology.scheduler.scheduleblock.dto.ScheduleBlockResponse
import pl.kacosmetology.scheduler.scheduleblock.dto.UpdateScheduleBlockRequest
import pl.kacosmetology.scheduler.scheduleblock.dto.toResponse
import pl.kacosmetology.scheduler.security.CustomUserDetails

/** REST API for managing company-scoped employee schedule blocks. All endpoints require OWNER or EMPLOYEE role. */
@RestController
@RequestMapping("/api/schedule-blocks")
class ScheduleBlockController(
    private val scheduleBlockService: ScheduleBlockService,
) {

    /**
     * Creates a non-overlapping schedule block for an employee of the authenticated company. OWNER may specify a target
     * employee via [CreateScheduleBlockRequest.employeeId]; EMPLOYEE may target only themselves.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun createBlock(
        @Valid @RequestBody request: CreateScheduleBlockRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): ScheduleBlockResponse {
        val requesterId = userDetails.id
        val companyId =
            userDetails.companyId ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisanej firmy")
        val isOwner = userDetails.authorities.any { it.authority == "ROLE_OWNER" }
        if (!isOwner && request.employeeId != null && request.employeeId != requesterId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Możesz tworzyć tylko własne blokady")
        }
        val targetEmployeeId = request.employeeId ?: requesterId

        return scheduleBlockService
            .createBlock(
                employeeId = targetEmployeeId,
                companyId = companyId,
                startTime = requireNotNull(request.startTime) { "Czas rozpoczęcia jest wymagany" },
                endTime = requireNotNull(request.endTime) { "Czas zakończenia jest wymagany" },
                reason = request.reason,
            )
            .toResponse()
    }

    /** Updates only the interval. OWNER may update company blocks; EMPLOYEE only their own. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun updateBlock(
        @PathVariable id: UUID,
        @RequestHeader("If-Match") revision: String,
        @Valid @RequestBody request: UpdateScheduleBlockRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): ScheduleBlockResponse {
        val expected =
            revision.removeSurrounding("\"").toLongOrNull()?.takeIf { it >= 0 }
                ?: throw ApiException(ErrorCode.REVISION_REQUIRED, "If-Match musi zawierać aktualną wersję blokady")
        return scheduleBlockService
            .updateBlock(
                blockId = id,
                requesterId = userDetails.id,
                isOwner = userDetails.authorities.any { it.authority == "ROLE_OWNER" },
                companyId = userDetails.requireCompanyId(),
                revision = expected,
                startTime = requireNotNull(request.startTime),
                endTime = requireNotNull(request.endTime),
            )
            .toResponse()
    }

    /**
     * Deletes a schedule block. OWNER may delete any block within their company; EMPLOYEE may only delete their own
     * blocks.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun deleteBlock(
        @PathVariable id: UUID,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ) {
        val requesterId = userDetails.id
        val isOwner = userDetails.authorities.any { it.authority == "ROLE_OWNER" }
        val companyId = userDetails.requireCompanyId()
        scheduleBlockService.deleteBlock(id, requesterId, isOwner, companyId)
    }

    /**
     * Returns schedule blocks within a time range. Every staff member may supply [employeeId] to read a colleague's
     * intervals and reasons within their company.
     */
    @GetMapping("/employee")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun getMyBlocks(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) employeeId: UUID?,
        @RequestParam start: LocalDateTime,
        @RequestParam end: LocalDateTime,
    ): List<ScheduleBlockResponse> {
        val requesterId = userDetails.id
        val companyId =
            userDetails.companyId ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisanej firmy")
        val targetId = employeeId ?: requesterId
        return scheduleBlockService.getEmployeeBlocks(companyId, targetId, start, end).map { it.toResponse() }
    }
}
