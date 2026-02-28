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
        @AuthenticationPrincipal userDetails: CustomUserDetails?
    ): ReservationResponse {
        val customerId = userDetails?.id
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Musisz być zalogowany, aby zarezerwować termin")

        return reservationService.createReservation(
            customerId = customerId,
            employeeId = request.employeeId,
            serviceId = request.serviceId,
            startTime = request.startTime
        ).toResponse()
    }

    /** Cancels the customer's own reservation. */
    @PatchMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelReservation(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails?
    ) {
        val customerId = userDetails?.id
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Brak autoryzacji")
        reservationService.cancelReservation(id, customerId)
    }

    /** Marks a reservation as completed. Requires OWNER or EMPLOYEE role. */
    @PatchMapping("/{id}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun completeReservation(@PathVariable id: Long) {
        reservationService.completeReservation(id)
    }

    /** Returns the authenticated customer's reservation history. */
    @GetMapping("/me")
    fun getMyReservations(@AuthenticationPrincipal userDetails: CustomUserDetails?): List<ReservationResponse> {
        val customerId = userDetails?.id ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return reservationService.getCustomerReservations(customerId).map { it.toResponse() }
    }

    /** Returns the authenticated employee's schedule within a time range. Requires OWNER or EMPLOYEE role. */
    @GetMapping("/employee")
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun getEmployeeSchedule(
        @AuthenticationPrincipal userDetails: CustomUserDetails?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) start: LocalDateTime,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) end: LocalDateTime
    ): List<EmployeeReservationResponse> {
        val employeeId = userDetails?.id ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return reservationService.getEmployeeSchedule(employeeId, start, end).map { it.toEmployeeResponse() }
    }

    /** Creates a reservation for a client by phone number. Requires OWNER or EMPLOYEE role. */
    @PostMapping("/staff")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")
    fun createReservationByStaff(
        @Valid @RequestBody request: StaffCreateReservationRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails?
    ): ReservationResponse {
        if (userDetails == null) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Brak autoryzacji")

        return reservationService.createReservationByStaff(
            employeeId = request.employeeId!!,
            serviceId = request.serviceId!!,
            startTime = request.startTime!!,
            customerPhone = request.customerPhone!!,
            customerFirstName = request.customerFirstName,
            customerLastName = request.customerLastName
        ).toResponse()
    }
}