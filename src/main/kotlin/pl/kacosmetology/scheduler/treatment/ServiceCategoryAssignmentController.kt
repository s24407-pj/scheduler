package pl.kacosmetology.scheduler.treatment

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.treatment.dto.AssignCategoryRequest

/** REST API for assigning categories to individual services. */
@RestController
@RequestMapping("/api/services")
class ServiceCategoryAssignmentController(
    private val serviceCategoryService: ServiceCategoryService
) {

    /**
     * Assigns or removes a category from a service.
     * Send `{"categoryId": null}` to remove the assignment.
     * Requires OWNER role.
     */
    @PatchMapping("/{serviceId}/category")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun assignCategory(
        @PathVariable serviceId: Long,
        @Valid @RequestBody request: AssignCategoryRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) = serviceCategoryService.assignCategory(serviceId, userDetails.companyId!!, request.categoryId)
}
