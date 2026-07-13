package pl.kacosmetology.scheduler.offering

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import pl.kacosmetology.scheduler.offering.dto.OfferingCategoryRequest
import pl.kacosmetology.scheduler.offering.dto.OfferingCategoryResponse
import pl.kacosmetology.scheduler.offering.dto.toOfferingCategoryResponse
import pl.kacosmetology.scheduler.security.CustomUserDetails

/**
 * REST API for managing offering categories.
 * OWNER and EMPLOYEE can read; only OWNER can create or delete.
 */
@RestController
@RequestMapping("/api/offering-categories")
class OfferingCategoryController(
    private val offeringCategoryService: OfferingCategoryService
) {

    /** Returns all categories for the authenticated user's company. */
    @GetMapping
    fun getCategories(@AuthenticationPrincipal userDetails: CustomUserDetails): List<OfferingCategoryResponse> =
        offeringCategoryService.getCategories(userDetails.requireCompanyId())
            .map { it.toOfferingCategoryResponse() }

    /** Creates a new category. Requires OWNER role. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    fun createCategory(
        @Valid @RequestBody request: OfferingCategoryRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): OfferingCategoryResponse =
        offeringCategoryService.createCategory(userDetails.requireCompanyId(), request).toOfferingCategoryResponse()

    /** Deletes a category by ID. Requires OWNER role. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun deleteCategory(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) = offeringCategoryService.deleteCategory(id, userDetails.requireCompanyId())
}
