package pl.kacosmetology.scheduler.user

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.util.*
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import pl.kacosmetology.scheduler.config.pageResponse
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.user.dto.CustomerStatusResponse
import pl.kacosmetology.scheduler.user.dto.UpdateCustomerNotesRequest

/** REST API for owner-scoped manual customer block/unblock operations. */
@RestController
@RequestMapping("/api/customers")
class CustomerController(
    private val customerService: CustomerService,
) {

    /**
     * Returns a bounded page of customers who have made at least one reservation at the authenticated user's company.
     * Requires OWNER or EMPLOYEE role.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun listCustomers(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) size: Int,
    ): ResponseEntity<List<CustomerStatusResponse>> =
        customerService.listCustomers(userDetails.requireCompanyId(), page, size).pageResponse(userDetails)

    /** Returns a customer's company-scoped block/no-show status. Requires OWNER or EMPLOYEE role. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun getCustomerStatus(
        @PathVariable id: UUID,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): CustomerStatusResponse {
        val companyId =
            userDetails.companyId ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisanej firmy")
        return customerService.getCustomerStatus(id, companyId)
    }

    /** Manually blocks a customer from booking online. Requires OWNER role. */
    @PatchMapping("/{id}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun blockCustomer(
        @PathVariable id: UUID,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ) {
        val companyId =
            userDetails.companyId ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisanej firmy")
        customerService.blockCustomer(id, companyId)
    }

    /** Sets free-text notes for a customer (company-scoped). Requires OWNER or EMPLOYEE role. */
    @PutMapping("/{id}/notes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun setCustomerNotes(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateCustomerNotesRequest,
        @RequestHeader("If-Match") revision: String,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ) {
        val companyId =
            userDetails.companyId ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisanej firmy")
        customerService.setCustomerNotes(id, companyId, request.notes, parseProfileRevision(revision))
    }

    /** Both staff roles may page through visits in the company's existing customer relationship. */
    @GetMapping("/{id}/visits")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun visits(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: CustomUserDetails,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) size: Int,
    ) = customerService.getVisitHistory(id, principal.requireCompanyId(), page, size).pageResponse(principal)

    /** Edits the salon's customer profile; names and email are staff-managed. */
    @PutMapping("/{id}/profile")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun updateProfile(
        @PathVariable id: UUID,
        @Valid @RequestBody request: pl.kacosmetology.scheduler.user.dto.UpdateCustomerProfileRequest,
        @RequestHeader("If-Match") revision: String,
        @AuthenticationPrincipal principal: CustomUserDetails,
    ) =
        customerService.updateProfile(
            id,
            principal.requireCompanyId(),
            request,
            parseProfileRevision(revision),
        )

    private fun parseProfileRevision(revision: String): Long =
        revision.removeSurrounding("\"").toLongOrNull()?.takeIf { it >= 0 }
            ?: throw pl.kacosmetology.scheduler.config.ApiException(
                pl.kacosmetology.scheduler.config.ErrorCode.REVISION_REQUIRED,
                "If-Match musi zawierać wersję profilu",
            )

    /** Unblocks a customer without resetting durable no-show history. Requires OWNER role. */
    @PatchMapping("/{id}/unblock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun unblockCustomer(
        @PathVariable id: UUID,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ) {
        val companyId =
            userDetails.companyId ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisanej firmy")
        customerService.unblockCustomer(id, companyId)
    }
}
