package pl.kacosmetology.scheduler.company

import java.util.*
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import pl.kacosmetology.scheduler.company.dto.CompanyEmployeeResponse
import pl.kacosmetology.scheduler.company.dto.PublicEmployeeResponse
import pl.kacosmetology.scheduler.security.CustomUserDetails

/**
 * REST API for listing a company's employees under `/api/employees`. The public listing requires no authentication; the
 * staff listing requires EMPLOYEE or OWNER role.
 */
@RestController
@RequestMapping("/api/employees")
class CompanyEmployeeController(
    private val companyService: CompanyService,
) {

    /** Returns employees eligible for [offeringId] at [companyId]. Public — no authentication required. */
    @GetMapping("/public/company/{companyId}")
    fun getPublicBookableEmployees(
        @PathVariable companyId: UUID,
        @RequestParam offeringId: UUID,
    ): List<PublicEmployeeResponse> = companyService.getPublicBookableEmployees(companyId, offeringId)

    /**
     * Returns all employees belonging to the authenticated user's company. `id` in each entry is the user ID (used as
     * the path parameter in sub-resource routes such as `/api/employees/{id}/work-schedule` and
     * `/api/employees/{id}/photo`).
     */
    @GetMapping("")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun listEmployees(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): List<CompanyEmployeeResponse> =
        companyService.getEmployees(requireCompanyId(userDetails)).map { it.copy(id = it.userId) }

    private fun requireCompanyId(userDetails: CustomUserDetails): UUID =
        userDetails.companyId ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisania do firmy")
}
