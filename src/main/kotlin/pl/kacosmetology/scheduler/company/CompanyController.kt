package pl.kacosmetology.scheduler.company

import jakarta.validation.Valid
import java.util.*
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import pl.kacosmetology.scheduler.company.dto.*
import pl.kacosmetology.scheduler.security.CustomUserDetails

/**
 * REST API for reading and updating company settings (business hours, slot interval). OWNER and EMPLOYEE can read; only
 * OWNER can update.
 */
@RestController
@RequestMapping("/api/company")
class CompanyController(
    private val companyService: CompanyService,
) {

    /** Returns public company profile for customer booking pages. No authentication required. */
    @GetMapping("/public/{companyId}")
    fun getPublicProfile(@PathVariable companyId: UUID): CompanyPublicProfileResponse =
        companyService.getPublicProfile(companyId)

    /** Returns all employees (OWNER + EMPLOYEE) of the authenticated user's company. */
    @GetMapping("/employees")
    fun getEmployees(@AuthenticationPrincipal userDetails: CustomUserDetails): List<CompanyEmployeeResponse> =
        companyService.getEmployees(userDetails.requireCompanyId())

    /** Returns the current company settings for the authenticated user's company. */
    @GetMapping("/settings")
    fun getSettings(@AuthenticationPrincipal userDetails: CustomUserDetails): CompanySettingsResponse =
        companyService.getCompany(userDetails.requireCompanyId())

    /** Updates company business hours and slot interval. Requires OWNER role. */
    @PutMapping("/settings")
    @PreAuthorize("hasRole('OWNER')")
    fun updateSettings(
        @Valid @RequestBody request: UpdateCompanySettingsRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): CompanySettingsResponse = companyService.updateSettings(userDetails.requireCompanyId(), request)

    /** Updates the owner's company-local public name and address. */
    @PutMapping("/profile")
    @PreAuthorize("hasRole('OWNER')")
    fun updateProfile(
        @Valid @RequestBody request: UpdateCompanyProfileRequest,
        @AuthenticationPrincipal principal: CustomUserDetails,
    ): CompanyPublicProfileResponse = companyService.updateProfile(principal.requireCompanyId(), request)
}
