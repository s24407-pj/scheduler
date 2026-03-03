package pl.kacosmetology.scheduler.offering

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import pl.kacosmetology.scheduler.offering.dto.AssignOfferingCategoryRequest
import pl.kacosmetology.scheduler.security.CustomUserDetails

/** REST API for assigning categories to individual offerings. */
@RestController
@RequestMapping("/api/offerings")
class OfferingCategoryAssignmentController(
    private val offeringCategoryService: OfferingCategoryService
) {

    /**
     * Assigns or removes a category from an offering.
     * Send `{"categoryId": null}` to remove the assignment.
     * Requires OWNER role.
     */
    @PatchMapping("/{offeringId}/category")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun assignCategory(
        @PathVariable offeringId: Long,
        @Valid @RequestBody request: AssignOfferingCategoryRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) = offeringCategoryService.assignCategory(offeringId, userDetails.companyId!!, request.categoryId)
}
