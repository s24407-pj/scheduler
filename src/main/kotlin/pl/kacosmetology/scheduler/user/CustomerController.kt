package pl.kacosmetology.scheduler.user

import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.user.dto.CustomerStatusResponse

/** REST API for owner-scoped manual customer block/unblock operations. */
@RestController
@RequestMapping("/api/customers")
class CustomerController(
    private val customerService: CustomerService
) {

    /** Returns a customer's company-scoped block/no-show status. Requires OWNER or EMPLOYEE role. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun getCustomerStatus(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails?
    ): CustomerStatusResponse {
        val companyId = userDetails?.companyId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Brak autoryzacji")
        return customerService.getCustomerStatus(id, companyId)
    }

    /** Manually blocks a customer from booking online. Requires OWNER role. */
    @PatchMapping("/{id}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun blockCustomer(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails?
    ) {
        val companyId = userDetails?.companyId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Brak autoryzacji")
        customerService.blockCustomer(id, companyId)
    }

    /** Unblocks a customer and resets their no-show counter. Requires OWNER role. */
    @PatchMapping("/{id}/unblock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun unblockCustomer(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails?
    ) {
        val companyId = userDetails?.companyId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Brak autoryzacji")
        customerService.unblockCustomer(id, companyId)
    }
}
