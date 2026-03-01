package pl.kacosmetology.scheduler.treatment

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.treatment.dto.CategoryRequest
import pl.kacosmetology.scheduler.treatment.dto.CategoryResponse
import pl.kacosmetology.scheduler.treatment.dto.toCategoryResponse

/**
 * REST API for managing service categories.
 * OWNER and EMPLOYEE can read; only OWNER can create or delete.
 */
@RestController
@RequestMapping("/api/categories")
class ServiceCategoryController(
    private val serviceCategoryService: ServiceCategoryService
) {

    /** Returns all categories for the authenticated user's company. */
    @GetMapping
    fun getCategories(@AuthenticationPrincipal userDetails: CustomUserDetails): List<CategoryResponse> =
        serviceCategoryService.getCategories(userDetails.companyId!!)
            .map { it.toCategoryResponse() }

    /** Creates a new category. Requires OWNER role. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    fun createCategory(
        @Valid @RequestBody request: CategoryRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): CategoryResponse =
        serviceCategoryService.createCategory(userDetails.companyId!!, request).toCategoryResponse()

    /** Deletes a category by ID. Requires OWNER role. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun deleteCategory(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) = serviceCategoryService.deleteCategory(id, userDetails.companyId!!)
}
