package pl.kacosmetology.scheduler.employeeoffering

import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import pl.kacosmetology.scheduler.employeeoffering.dto.EmployeeOfferingAssignmentResponse
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.security.CustomUserDetails

/**
 * REST API for managing employee–offering assignments.
 * Public read endpoint for customers; staff endpoints require authentication; write endpoints require OWNER role.
 */
@RestController
class EmployeeOfferingController(
    private val employeeOfferingService: EmployeeOfferingService
) {

    /** Returns active offerings assigned to the given employee. Public — no authentication required. */
    @GetMapping("/api/offerings/public/employee/{employeeId}")
    fun getPublicEmployeeOfferings(@PathVariable employeeId: Long): List<Offering> =
        employeeOfferingService.getOfferingsForEmployee(employeeId)

    /** Returns all offering assignments for the given employee. Requires staff authentication. */
    @GetMapping("/api/employees/{employeeId}/offerings")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun getAssignments(
        @PathVariable employeeId: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): List<EmployeeOfferingAssignmentResponse> =
        employeeOfferingService.getAssignments(userDetails.requireCompanyId(), employeeId)

    /** Assigns an offering to an employee. Requires OWNER role. */
    @PostMapping("/api/employees/{employeeId}/offerings/{offeringId}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    fun assignOffering(
        @PathVariable employeeId: Long,
        @PathVariable offeringId: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): EmployeeOfferingAssignmentResponse =
        employeeOfferingService.assignOffering(userDetails.requireCompanyId(), employeeId, offeringId)

    /** Removes an offering assignment from an employee. Requires OWNER role. */
    @DeleteMapping("/api/employees/{employeeId}/offerings/{offeringId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun removeAssignment(
        @PathVariable employeeId: Long,
        @PathVariable offeringId: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) = employeeOfferingService.removeAssignment(userDetails.requireCompanyId(), employeeId, offeringId)
}
