package pl.kacosmetology.scheduler.user

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.user.dto.CustomerStatusResponse
import pl.kacosmetology.scheduler.user.dto.UpdateCustomerNotesRequest

/** REST API for owner-scoped manual customer block/unblock operations. */
@RestController
@RequestMapping("/api/customers")
class CustomerController(
    private val customerService: CustomerService
) {

    /** Returns all customers who have made at least one reservation at the authenticated user's company. Requires OWNER or EMPLOYEE role. */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun listCustomers(@AuthenticationPrincipal userDetails: CustomUserDetails): List<CustomerStatusResponse> {
        val companyId = userDetails.companyId
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisanej firmy")
        return customerService.listCustomers(companyId)
    }

    /** Returns a customer's company-scoped block/no-show status. Requires OWNER or EMPLOYEE role. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun getCustomerStatus(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): CustomerStatusResponse {
        val companyId = userDetails.companyId
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisanej firmy")
        return customerService.getCustomerStatus(id, companyId)
    }

    /** Manually blocks a customer from booking online. Requires OWNER role. */
    @PatchMapping("/{id}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun blockCustomer(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) {
        val companyId = userDetails.companyId
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisanej firmy")
        customerService.blockCustomer(id, companyId)
    }

    /** Sets free-text notes for a customer (company-scoped). Requires OWNER or EMPLOYEE role. */
    @PutMapping("/{id}/notes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun setCustomerNotes(
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateCustomerNotesRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) {
        val companyId = userDetails.companyId
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisanej firmy")
        customerService.setCustomerNotes(id, companyId, request.notes)
    }

    /** Unblocks a customer and resets their no-show counter. Requires OWNER role. */
    @PatchMapping("/{id}/unblock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun unblockCustomer(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) {
        val companyId = userDetails.companyId
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisanej firmy")
        customerService.unblockCustomer(id, companyId)
    }
}
