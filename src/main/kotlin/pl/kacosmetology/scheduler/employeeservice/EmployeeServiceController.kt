package pl.kacosmetology.scheduler.employeeservice

import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import pl.kacosmetology.scheduler.employeeservice.dto.EmployeeServiceAssignmentResponse
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.treatment.ProvidedService

/**
 * REST API for managing employee–service assignments.
 * Public read endpoint for customers; staff endpoints require authentication; write endpoints require OWNER role.
 */
@RestController
class EmployeeServiceController(
    private val employeeServiceManagementService: EmployeeServiceManagementService
) {

    /** Returns active services assigned to the given employee. Public — no authentication required. */
    @GetMapping("/api/services/public/employee/{employeeId}")
    fun getPublicEmployeeServices(@PathVariable employeeId: Long): List<ProvidedService> =
        employeeServiceManagementService.getServicesForEmployee(employeeId)

    /** Returns all service assignments for the given employee. Requires authentication. */
    @GetMapping("/api/employees/{employeeId}/services")
    fun getAssignments(
        @PathVariable employeeId: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): List<EmployeeServiceAssignmentResponse> = employeeServiceManagementService.getAssignments(employeeId)

    /** Assigns a service to an employee. Requires OWNER role. */
    @PostMapping("/api/employees/{employeeId}/services/{serviceId}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    fun assignService(
        @PathVariable employeeId: Long,
        @PathVariable serviceId: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): EmployeeServiceAssignmentResponse =
        employeeServiceManagementService.assignService(userDetails.companyId!!, employeeId, serviceId)

    /** Removes a service assignment from an employee. Requires OWNER role. */
    @DeleteMapping("/api/employees/{employeeId}/services/{serviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun removeAssignment(
        @PathVariable employeeId: Long,
        @PathVariable serviceId: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) = employeeServiceManagementService.removeAssignment(userDetails.companyId!!, employeeId, serviceId)
}
