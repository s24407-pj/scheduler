package pl.kacosmetology.scheduler.reservation

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import java.time.LocalDateTime
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import pl.kacosmetology.scheduler.reservation.dto.*
import pl.kacosmetology.scheduler.security.CustomUserDetails

/** Cookie-authenticated booking, revision-bound lifecycle operations, and company calendars. */
@RestController
@RequestMapping("/api/reservations")
class ReservationController(
    private val reservationService: ReservationService,
    private val submissions: BookingSubmissionService,
) {
    /** Required durable submission identity binds the original payload, including after a failed booking attempt. */
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    fun createReservation(
        @Valid @RequestBody request: CreateReservationRequest,
        @RequestHeader("Idempotency-Key") key: String,
        httpRequest: HttpServletRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): ResponseEntity<ReservationResponse> =
        submissions
            .execute(
                userDetails.accountKey(),
                key,
                request,
                httpRequest.getAttribute(BOOKING_PAYLOAD_ATTRIBUTE) as? String,
            ) {
                reservationService.createReservation(
                    userDetails.id,
                    request.employeeId,
                    request.serviceId,
                    request.startTime,
                    firstName = request.firstName,
                    lastName = request.lastName,
                    contactEmail = request.contactEmail,
                    quoteId = request.quoteId,
                )
            }
            .toResponseEntity()

    /** Both staff roles may create company bookings; regular price and notification-off are the defaults. */
    @PostMapping("/staff")
    @PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
    fun createReservationByStaff(
        @Valid @RequestBody request: StaffCreateReservationRequest,
        @RequestHeader("Idempotency-Key") key: String,
        httpRequest: HttpServletRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): ResponseEntity<ReservationResponse> =
        submissions
            .execute(
                userDetails.accountKey(),
                key,
                request,
                httpRequest.getAttribute(BOOKING_PAYLOAD_ATTRIBUTE) as? String,
            ) {
                reservationService.createReservationByStaff(
                    requireNotNull(request.employeeId),
                    requireNotNull(request.serviceId),
                    requireNotNull(request.startTime),
                    requireNotNull(request.customerPhone),
                    request.customerFirstName,
                    request.customerLastName,
                    userDetails.requireCompanyId(),
                    request.price,
                    request.durationMinutes,
                    request.overrideEmployeeBlock,
                    userDetails.isOwner(),
                    request.notifyCustomer,
                    request.internalNotes,
                )
            }
            .toResponseEntity(includeInternal = true)

    /** Direct edits preserve ID/creation time and require the last observed revision. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
    fun edit(
        @PathVariable id: UUID,
        @RequestHeader("If-Match") revision: String,
        @Valid @RequestBody request: EditReservationRequest,
        @AuthenticationPrincipal user: CustomUserDetails,
    ): ReservationResponse =
        reservationService.response(
            reservationService.editReservation(
                id,
                user.requireCompanyId(),
                revision(revision),
                request,
                user.isOwner(),
            ),
            includeInternal = true,
        )

    /** Owning customers may cancel strictly before the scheduled start. */
    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelReservation(
        @PathVariable id: UUID,
        @RequestHeader("If-Match") revision: String,
        @Valid @RequestBody(required = false) request: ReservationActionRequest?,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ) {
        reservationService.cancelReservation(id, userDetails.id, revision(revision), request?.reason)
    }

    /** Staff cancellation reason is required and remains private. */
    @PatchMapping("/{id}/cancel-by-staff")
    @PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelReservationByStaff(
        @PathVariable id: UUID,
        @RequestHeader("If-Match") revision: String,
        @Valid @RequestBody request: ReservationActionRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ) {
        reservationService.cancelReservationByStaff(
            id,
            userDetails.requireCompanyId(),
            revision(revision),
            request,
            userDetails.isOwner(),
        )
    }

    /** Completion is available from scheduled start, including correction of a mistaken no-show. */
    @PatchMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun completeReservation(
        @PathVariable id: UUID,
        @RequestHeader("If-Match") revision: String,
        @Valid @RequestBody(required = false) request: ReservationActionRequest?,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ) {
        reservationService.completeReservation(
            id,
            userDetails.requireCompanyId(),
            revision(revision),
            request ?: ReservationActionRequest(),
            userDetails.isOwner(),
        )
    }

    /** Marks no-show with role-specific correction deadline and a durable penalty event. */
    @PatchMapping("/{id}/no-show")
    @PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markNoShow(
        @PathVariable id: UUID,
        @RequestHeader("If-Match") revision: String,
        @Valid @RequestBody(required = false) request: ReservationActionRequest?,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ) {
        reservationService.markNoShow(
            id,
            userDetails.requireCompanyId(),
            revision(revision),
            request ?: ReservationActionRequest(),
            userDetails.isOwner(),
        )
    }

    /** Restores a cancelled visit, or reopens an ongoing mistaken no-show, under current hard rules. */
    @PatchMapping("/{id}/restore")
    @PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun restore(
        @PathVariable id: UUID,
        @RequestHeader("If-Match") revision: String,
        @Valid @RequestBody(required = false) request: ReservationActionRequest?,
        @AuthenticationPrincipal user: CustomUserDetails,
    ) {
        reservationService.restoreReservation(
            id,
            user.requireCompanyId(),
            revision(revision),
            request ?: ReservationActionRequest(),
            user.isOwner(),
        )
    }

    /** Archives only cancelled appointments, retaining audit and replay identity. */
    @PatchMapping("/{id}/archive")
    @PreAuthorize("hasRole('OWNER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun archive(
        @PathVariable id: UUID,
        @RequestHeader("If-Match") revision: String,
        @AuthenticationPrincipal user: CustomUserDetails,
    ) {
        reservationService.archiveReservation(id, user.requireCompanyId(), revision(revision), true)
    }

    /** Unarchiving is required before restoration. */
    @PatchMapping("/{id}/unarchive")
    @PreAuthorize("hasRole('OWNER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unarchive(
        @PathVariable id: UUID,
        @RequestHeader("If-Match") revision: String,
        @AuthenticationPrincipal user: CustomUserDetails,
    ) {
        reservationService.archiveReservation(id, user.requireCompanyId(), revision(revision), false)
    }

    /** Lists own unarchived history without staff-only notes or reasons. */
    @GetMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun getMyReservations(@AuthenticationPrincipal userDetails: CustomUserDetails): List<ReservationResponse> =
        reservationService.getCustomerReservations(userDetails.id).map(reservationService::response)

    /** Reads the caller's calendar in the authenticated company. */
    @GetMapping("/employee")
    @PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
    fun getEmployeeSchedule(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam start: LocalDateTime,
        @RequestParam end: LocalDateTime,
    ): List<EmployeeReservationResponse> =
        reservationService.getEmployeeSchedule(userDetails.requireCompanyId(), userDetails.id, start, end).map {
            it.toEmployeeResponse()
        }

    /** Both staff roles may read company calendars and company-local customer visit information. */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
    fun getReservations(
        @RequestParam employeeId: UUID,
        @RequestParam start: LocalDateTime,
        @RequestParam end: LocalDateTime,
        @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): List<DashboardReservationResponse> =
        reservationService.getCompanyReservations(userDetails.requireCompanyId(), employeeId, start, end)

    private fun BookingResult.toResponseEntity(includeInternal: Boolean = false): ResponseEntity<ReservationResponse> =
        ResponseEntity.status(if (created) HttpStatus.CREATED else HttpStatus.OK)
            .eTag(reservation.version.toString())
            .body(reservationService.response(reservation, includeInternal))

    private fun revision(header: String): Long =
        header.removeSurrounding("\"").toLongOrNull()?.takeIf { it >= 0 }
            ?: throw pl.kacosmetology.scheduler.config.ApiException(
                pl.kacosmetology.scheduler.config.ErrorCode.REVISION_REQUIRED,
                "If-Match musi zawierać aktualną wersję wizyty",
            )

    private fun CustomUserDetails.isOwner(): Boolean = role == "ROLE_OWNER"
}
