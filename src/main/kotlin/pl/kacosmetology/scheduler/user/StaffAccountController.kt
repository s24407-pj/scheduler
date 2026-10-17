package pl.kacosmetology.scheduler.user

import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.user.dto.*

/** Owner employee administration; role provisioning stays operator-controlled. */
@RestController
@RequestMapping("/api/employees")
@PreAuthorize("hasRole('OWNER')")
class StaffAccountController(private val service: StaffAccountService) {
    /** Creates an employee account and returns its initial setup link to the owner. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal principal: CustomUserDetails,
        @Valid @RequestBody request: CreateEmployeeRequest,
    ) = service.create(principal.requireCompanyId(), request)

    /** Edits a company employee's profile and login address. */
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: CustomUserDetails,
        @Valid @RequestBody request: UpdateEmployeeRequest,
    ) = service.update(principal.requireCompanyId(), id, request)

    /** Changes access state and reports future appointments without cancelling them. */
    @PutMapping("/{id}/enabled")
    fun enabled(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: CustomUserDetails,
        @Valid @RequestBody request: SetEmployeeEnabledRequest,
    ) = service.setEnabled(principal.requireCompanyId(), id, request.enabled)

    /** Issues a replacement initial-setup/recovery link for secure handoff by the owner. */
    @PostMapping("/{id}/password-link")
    fun issueLink(@PathVariable id: UUID, @AuthenticationPrincipal principal: CustomUserDetails) =
        service.issueLink(principal.requireCompanyId(), id)
}

/** Staff self-service keeps password verification separate from session authentication. */
@RestController
@RequestMapping("/api/staff")
@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
class StaffSelfController(private val service: StaffAccountService) {
    /** Reads current company-owned presentation. */
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: CustomUserDetails) =
        service.getOwn(requireNotNull(principal.employmentId))

    /** Changes password and revokes all sessions, including the requesting browser. */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(
        @AuthenticationPrincipal principal: CustomUserDetails,
        @Valid @RequestBody request: ChangeStaffPasswordRequest,
    ) = service.changePassword(requireNotNull(principal.employmentId), request)
}

/** Anonymous CSRF-protected setup/recovery consumption never creates an authenticated session. */
@RestController
@RequestMapping("/api/auth/staff-password")
class StaffPasswordController(private val service: StaffAccountService) {
    /** Consumes a valid one-use 24-hour setup/recovery link. */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun setPassword(@Valid @RequestBody request: SetStaffPasswordRequest) = service.setPassword(request)
}
