package pl.kacosmetology.scheduler.reservation

import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import pl.kacosmetology.scheduler.reservation.dto.*
import pl.kacosmetology.scheduler.security.CustomUserDetails
import java.time.LocalDateTime

/**
 * REST API for managing reservations from both customer and staff perspectives.
 * Customers can create and cancel their own reservations; staff can view schedules and complete bookings.
 */
@RestController
@RequestMapping("/api/reservations")
class ReservationController(
    private val reservationService: ReservationService
) {

    /** Creates a new reservation for the authenticated customer. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createReservation(
        @Valid @RequestBody request: CreateReservationRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ReservationResponse {
        return reservationService.createReservation(
            customerId = userDetails.id,
            employeeId = request.employeeId,
            offeringId = request.serviceId,
            startTime = request.startTime
        ).toResponse()
    }

    /** Cancels the customer's own reservation. */
    @PatchMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelReservation(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) {
        reservationService.cancelReservation(id, userDetails.id)
    }

    /** Marks a reservation as completed. Requires OWNER or EMPLOYEE role. */
    @PatchMapping("/{id}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun completeReservation(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) {
        val companyId = userDetails.companyId ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        reservationService.completeReservation(id, companyId)
    }

    /** Marks a reservation as no-show and increments the customer's counter. Requires OWNER or EMPLOYEE role. */
    @PatchMapping("/{id}/no-show")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun markNoShow(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) {
        val companyId = userDetails.companyId ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        reservationService.markNoShow(id, companyId)
    }

    /** Returns the authenticated customer's reservation history. */
    @GetMapping("/me")
    fun getMyReservations(@AuthenticationPrincipal userDetails: CustomUserDetails): List<ReservationResponse> {
        return reservationService.getCustomerReservations(userDetails.id).map { it.toResponse() }
    }

    /** Returns the authenticated employee's schedule within a time range. Requires OWNER or EMPLOYEE role. */
    @GetMapping("/employee")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun getEmployeeSchedule(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) start: LocalDateTime,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) end: LocalDateTime
    ): List<EmployeeReservationResponse> {
        return reservationService.getEmployeeSchedule(userDetails.id, start, end).map { it.toEmployeeResponse() }
    }

    /**
     * Returns reservations for the given employee filtered by date range.
     * OWNER can query any employee within their company; EMPLOYEE can only query their own schedule.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun getReservations(
        @RequestParam employeeId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) start: LocalDateTime,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) end: LocalDateTime,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): List<DashboardReservationResponse> {
        val companyId = userDetails.companyId ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        val isOwner = userDetails.authorities.any { it.authority == "ROLE_OWNER" }
        if (!isOwner && userDetails.id != employeeId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Możesz przeglądać tylko swoje rezerwacje")
        }
        return reservationService.getCompanyReservations(companyId, employeeId, start, end)
    }

    /** Permanently deletes a reservation. Requires OWNER role. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    fun deleteReservation(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) {
        val companyId = userDetails.companyId ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        reservationService.deleteReservation(id, companyId)
    }

    /** Creates a reservation for a client by phone number. Requires OWNER or EMPLOYEE role. */
    @PostMapping("/staff")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun createReservationByStaff(
        @Valid @RequestBody request: StaffCreateReservationRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ReservationResponse {
        val companyId = userDetails.companyId ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)
        return reservationService.createReservationByStaff(
            employeeId = request.employeeId!!,
            serviceId = request.serviceId!!,
            startTime = request.startTime!!,
            customerPhone = request.customerPhone!!,
            customerFirstName = request.customerFirstName,
            customerLastName = request.customerLastName,
            requesterCompanyId = companyId
        ).toResponse()
    }
}
