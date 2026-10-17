package pl.kacosmetology.scheduler.employeeoffering

import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import pl.kacosmetology.scheduler.company.EmployeeEligibilityMode
import pl.kacosmetology.scheduler.employeeoffering.dto.EmployeeOfferingAssignmentResponse
import pl.kacosmetology.scheduler.security.CustomUserDetails

/**
 * REST API for managing employee–offering assignments. Read endpoints require staff authentication; write endpoints
 * require OWNER role. The public list of an employee's offerings is served by
 * [pl.kacosmetology.scheduler.offering.OfferingController].
 */
@RestController
class EmployeeOfferingController(
    private val employeeOfferingService: EmployeeOfferingService,
) {

    /** Returns all offering assignments for the given employee. Requires staff authentication. */
    @GetMapping("/api/employees/{employeeId}/offerings")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun getAssignments(
        @PathVariable employeeId: UUID,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): List<EmployeeOfferingAssignmentResponse> =
        employeeOfferingService.getAssignments(userDetails.requireCompanyId(), employeeId)

    /** Assigns an offering to an employee. Requires OWNER role. */
    @PostMapping("/api/employees/{employeeId}/offerings/{offeringId}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    fun assignOffering(
        @PathVariable employeeId: UUID,
        @PathVariable offeringId: UUID,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): EmployeeOfferingAssignmentResponse =
        employeeOfferingService.assignOffering(userDetails.requireCompanyId(), employeeId, offeringId)

    /** Removes an offering assignment from an employee. Requires OWNER role. */
    @DeleteMapping("/api/employees/{employeeId}/offerings/{offeringId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun removeAssignment(
        @PathVariable employeeId: UUID,
        @PathVariable offeringId: UUID,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ) = employeeOfferingService.removeAssignment(userDetails.requireCompanyId(), employeeId, offeringId)

    /** Selects explicit all-service or specific-service qualification; only the owner manages it. */
    @PatchMapping("/api/employees/{employeeId}/offering-eligibility")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun setEligibility(
        @PathVariable employeeId: UUID,
        @Valid @RequestBody request: EmployeeEligibilityRequest,
        @AuthenticationPrincipal principal: CustomUserDetails,
    ) = employeeOfferingService.setEligibility(principal.requireCompanyId(), employeeId, requireNotNull(request.mode))
}

/** An explicit qualification mode; an empty specific-service assignment set permits no services. */
data class EmployeeEligibilityRequest(
    @field:NotNull(message = "Tryb kwalifikacji jest wymagany") val mode: EmployeeEligibilityMode?,
)
