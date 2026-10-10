package pl.kacosmetology.scheduler.reservation

import java.time.Clock
import java.time.LocalDateTime
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.availability.BookableSlotPolicy
import pl.kacosmetology.scheduler.availability.EmployeeAvailabilityPolicy
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.company.effectivePrice
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.dto.DashboardReservationResponse
import pl.kacosmetology.scheduler.reservation.dto.toDashboardResponse
import pl.kacosmetology.scheduler.user.CompanyCustomerBlockRepository
import pl.kacosmetology.scheduler.user.UserRepository

/** How long after a visit's end a COMPLETED reservation can still be corrected to NO_SHOW. */
const val NO_SHOW_CORRECTION_HOURS = 24L

/** Core business logic for reservation lifecycle: create, cancel, complete, and no-show marking. */
@Service
class ReservationService(
    private val reservationRepository: ReservationRepository,
    private val offeringRepository: OfferingRepository,
    private val userRepository: UserRepository,
    private val assignmentRepository: EmployeeOfferingAssignmentRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
    private val companyRepository: CompanyRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val companyCustomerBlockRepository: CompanyCustomerBlockRepository,
    private val employeeAvailabilityPolicy: EmployeeAvailabilityPolicy,
    private val bookableSlotPolicy: BookableSlotPolicy,
    private val clock: Clock,
) {
    /**
     * Creates a new reservation with a price snapshot from the offering catalog, or returns the customer's existing
     * active reservation for the same employee, offering, and [startTime] so retried requests are idempotent. The
     * existing-booking check runs under the employment row lock, so concurrent retries cannot both create.
     *
     * Throws [ApiException] when [startTime] does not exist in the business time zone (daylight-saving gap), the
     * offering is unknown or inactive, the employee is not part of the offering's company or does not perform it, the
     * customer is blocked, the slot is taken, [enforceAdvanceCheck] is true and the slot starts sooner than the
     * company's [pl.kacosmetology.scheduler.company.Company.minBookingAdvanceMinutes] setting, or [enforceSlotGrid] is
     * true and [startTime] is not a slot offered by [BookableSlotPolicy].
     *
     * @param enforceAdvanceCheck when false (staff bookings) the minimum-advance-time check is skipped.
     * @param enforceSlotGrid when false (staff bookings) work hours and the slot grid are not enforced.
     */
    @Transactional
    fun createReservation(
        customerId: Long,
        employeeId: Long,
        offeringId: Long,
        startTime: LocalDateTime,
        enforceAdvanceCheck: Boolean = true,
        enforceSlotGrid: Boolean = true,
    ): BookingResult {
        if (clock.zone.rules.getValidOffsets(startTime).isEmpty()) {
            throw ApiException(ErrorCode.INVALID_LOCAL_TIME, "Podana godzina nie istnieje z powodu zmiany czasu")
        }

        if (!userRepository.existsById(customerId)) {
            throw ApiException(ErrorCode.CUSTOMER_NOT_FOUND, "Klient nie istnieje")
        }

        val offering =
            offeringRepository.findById(offeringId).orElseThrow {
                ApiException(ErrorCode.OFFERING_UNAVAILABLE, "Usługa nie istnieje")
            }

        if (!offering.active) {
            throw ApiException(ErrorCode.OFFERING_UNAVAILABLE, "Ta usługa nie jest już dostępna")
        }

        if (companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(offering.companyId, employeeId) == null) {
            throw ApiException(ErrorCode.EMPLOYEE_NOT_IN_COMPANY, "Pracownik nie należy do firmy wybranej usługi")
        }

        reservationRepository.findActiveBooking(customerId, employeeId, offeringId, startTime)?.let {
            return BookingResult(it, created = false)
        }

        if (
            assignmentRepository.existsByCompanyIdAndEmployeeId(offering.companyId, employeeId) &&
                !assignmentRepository.existsByEmployeeIdAndOfferingId(employeeId, offeringId)
        ) {
            throw ApiException(ErrorCode.EMPLOYEE_OFFERING_NOT_ASSIGNED, "Ten pracownik nie wykonuje wybranej usługi")
        }

        val block = companyCustomerBlockRepository.findByCompanyIdAndCustomerId(offering.companyId, customerId)
        if (block?.blocked == true) {
            throw ApiException(
                ErrorCode.CUSTOMER_BLOCKED,
                "Klient jest zablokowany w tej firmie i nie może rezerwować online",
            )
        }

        val company =
            companyRepository.findById(offering.companyId).orElseThrow { NoSuchElementException("Firma nie istnieje") }

        val now = LocalDateTime.now(clock)
        if (enforceAdvanceCheck && company.minBookingAdvanceMinutes > 0) {
            val earliestAllowed = now.plusMinutes(company.minBookingAdvanceMinutes.toLong())
            if (!startTime.isAfter(earliestAllowed)) {
                throw ApiException(
                    ErrorCode.BOOKING_ADVANCE_REQUIRED,
                    "Rezerwację można złożyć co najmniej ${company.minBookingAdvanceMinutes} minut przed wizytą",
                )
            }
        }

        if (
            enforceSlotGrid &&
                !bookableSlotPolicy.isOnGrid(
                    offering.companyId,
                    employeeId,
                    startTime,
                    offering.durationMinutes,
                    company.slotIntervalMinutes,
                )
        ) {
            throw ApiException(ErrorCode.SLOT_UNAVAILABLE, "Ten termin nie jest dostępny do rezerwacji")
        }

        val endTime = startTime.plusMinutes(offering.durationMinutes.toLong())

        employeeAvailabilityPolicy.assertAvailable(offering.companyId, employeeId, startTime, endTime)

        val price = company.effectivePrice(offering.price, startTime, now)

        val saved =
            reservationRepository.save(
                Reservation(
                    companyId = offering.companyId,
                    customerId = customerId,
                    employeeId = employeeId,
                    serviceId = offeringId,
                    price = price,
                    startTime = startTime,
                    endTime = endTime,
                    status = ReservationStatus.PENDING,
                ),
            )
        applicationEventPublisher.publishEvent(ReservationCreatedEvent(saved))
        return BookingResult(saved, created = true)
    }

    /**
     * Cancels a reservation on behalf of its owning customer. Allowed only before the visit starts and only if the
     * reservation is not already in a terminal state. Another customer's reservation is reported as not found.
     */
    @Transactional
    fun cancelReservation(reservationId: Long, customerId: Long) {
        val reservation = findReservation(reservationId) { it.customerId == customerId }

        reservation.cancel(onlyBeforeStartAt = LocalDateTime.now(clock))
        reservationRepository.save(reservation)
        applicationEventPublisher.publishEvent(ReservationCancelledEvent(reservation))
    }

    /**
     * Cancels a reservation on behalf of staff of [companyId]. Allowed at any time, including after the visit started,
     * as long as the reservation is not in a terminal state. Other companies' reservations are not found.
     */
    @Transactional
    fun cancelReservationByStaff(reservationId: Long, companyId: Long) {
        val reservation = findReservation(reservationId) { it.companyId == companyId }

        reservation.cancel()
        reservationRepository.save(reservation)
        applicationEventPublisher.publishEvent(ReservationCancelledEvent(reservation))
    }

    /** Marks a reservation as completed. Called by staff members; other companies' reservations are not found. */
    @Transactional
    fun completeReservation(reservationId: Long, companyId: Long) {
        val reservation = findReservation(reservationId) { it.companyId == companyId }

        reservation.complete()
        reservationRepository.save(reservation)
    }

    /**
     * Marks a reservation as [ReservationStatus.NO_SHOW] and increments the customer's company-scoped no-show counter.
     * If the counter reaches the company's [pl.kacosmetology.scheduler.company.Company.maxNoShows] threshold (and it is
     * greater than zero), the customer is automatically blocked from online booking at that company. PENDING or
     * CONFIRMED reservations can be marked once the visit has started; COMPLETED reservations (by staff or the
     * auto-complete job) can be corrected to no-show until [NO_SHOW_CORRECTION_HOURS] after the visit ended.
     */
    @Transactional
    fun markNoShow(reservationId: Long, companyId: Long) {
        val reservation = findReservation(reservationId) { it.companyId == companyId }

        reservation.markNoShow(LocalDateTime.now(clock))
        reservationRepository.save(reservation)

        companyCustomerBlockRepository.incrementNoShowCountAndApplyBlock(
            reservation.companyId,
            reservation.customerId,
        )
    }

    /** Returns all reservations for a customer, ordered by start time descending. */
    @Transactional(readOnly = true)
    fun getCustomerReservations(customerId: Long): List<Reservation> {
        return reservationRepository.findAllByCustomerIdOrderByStartTimeDesc(customerId)
    }

    /**
     * Returns reservations for a given employee within a company and date range, enriched with customer names. Used by
     * the owner dashboard — the authorization check (OWNER can see any employee, EMPLOYEE can only see their own) is
     * performed in the controller before calling this method.
     */
    @Transactional(readOnly = true)
    fun getCompanyReservations(
        companyId: Long,
        employeeId: Long,
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<DashboardReservationResponse> {
        val reservations =
            reservationRepository.findByCompanyIdAndEmployeeIdAndDateRange(companyId, employeeId, start, end)
        val customerIds = reservations.map { it.customerId }.distinct()
        val usersById = userRepository.findAllById(customerIds).associateBy { it.id }
        return reservations.map { r ->
            val customer = usersById[r.customerId]
            r.toDashboardResponse(
                customerFirstName = customer?.firstName ?: "",
                customerLastName = customer?.lastName ?: "",
            )
        }
    }

    /** Returns an employee's schedule at [companyId] within a given time range. */
    @Transactional(readOnly = true)
    fun getEmployeeSchedule(
        companyId: Long,
        employeeId: Long,
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<Reservation> {
        return reservationRepository.findEmployeeSchedule(companyId, employeeId, start, end)
    }

    /**
     * Permanently deletes a reservation. Only the owning company may delete; other companies' reservations are reported
     * as not found.
     */
    @Transactional
    fun deleteReservation(reservationId: Long, companyId: Long) {
        val reservation = findReservation(reservationId) { it.companyId == companyId }
        reservationRepository.delete(reservation)
    }

    /**
     * Creates a reservation on behalf of a client, identified by phone number, with the same idempotency as
     * [createReservation]. If no user with [customerPhone] exists, a new account is created using [customerFirstName]
     * and [customerLastName]; both are required in that case. A concurrent request creating the same phone number
     * resolves to the account that won the insert.
     *
     * @param requesterCompanyId authenticated staff member's company; the selected offering must belong to that company
     *   before any customer account is created.
     */
    @Transactional
    fun createReservationByStaff(
        employeeId: Long,
        serviceId: Long,
        startTime: LocalDateTime,
        customerPhone: String,
        customerFirstName: String?,
        customerLastName: String?,
        requesterCompanyId: Long,
    ): BookingResult {
        val offering =
            offeringRepository.findById(serviceId).orElseThrow {
                ApiException(ErrorCode.OFFERING_UNAVAILABLE, "Usługa nie istnieje")
            }
        if (offering.companyId != requesterCompanyId) {
            throw ApiException(ErrorCode.OFFERING_UNAVAILABLE, "Usługa nie należy do firmy pracownika")
        }

        val customer =
            userRepository.findByPhoneNumber(customerPhone)
                ?: run {
                    if (customerFirstName.isNullOrBlank() || customerLastName.isNullOrBlank()) {
                        throw ApiException(
                            ErrorCode.CUSTOMER_NAME_REQUIRED,
                            "Imię i nazwisko klienta są wymagane przy tworzeniu nowego konta",
                        )
                    }
                    userRepository.insertIfPhoneAbsent(customerPhone, customerFirstName, customerLastName)
                    checkNotNull(userRepository.findByPhoneNumber(customerPhone)) {
                        "Customer must exist after insertIfPhoneAbsent"
                    }
                }

        return createReservation(
            customerId = requireNotNull(customer.id) { "Persisted customer must have an ID" },
            employeeId = employeeId,
            offeringId = serviceId,
            startTime = startTime,
            enforceAdvanceCheck = false,
            enforceSlotGrid = false,
        )
    }

    private fun findReservation(reservationId: Long, isVisible: (Reservation) -> Boolean): Reservation =
        reservationRepository.findById(reservationId).orElse(null)?.takeIf(isVisible)
            ?: throw ApiException(ErrorCode.RESERVATION_NOT_FOUND, "Rezerwacja nie istnieje")
}

private fun invalidTransition(message: String): Nothing =
    throw ApiException(ErrorCode.INVALID_STATUS_TRANSITION, message)

/**
 * Cancels an active reservation; when [onlyBeforeStartAt] is given, that moment must be before [Reservation.startTime].
 */
private fun Reservation.cancel(onlyBeforeStartAt: LocalDateTime? = null) {
    when (status) {
        ReservationStatus.CANCELLED -> invalidTransition("Rezerwacja jest już anulowana")
        ReservationStatus.COMPLETED -> invalidTransition("Nie można anulować wizyty, która już się odbyła")
        ReservationStatus.NO_SHOW -> invalidTransition("Nie można anulować rezerwacji oznaczonej jako nieobecność")
        else -> {
            if (onlyBeforeStartAt != null && !onlyBeforeStartAt.isBefore(startTime)) {
                invalidTransition("Nie można anulować wizyty, która już się rozpoczęła")
            }
            status = ReservationStatus.CANCELLED
        }
    }
}

private fun Reservation.complete() {
    when (status) {
        ReservationStatus.CANCELLED -> invalidTransition("Nie można zakończyć odwołanej wizyty")
        ReservationStatus.COMPLETED -> invalidTransition("Wizyta jest już zakończona")
        ReservationStatus.NO_SHOW -> invalidTransition("Nie można zakończyć wizyty oznaczonej jako nieobecność")
        else -> status = ReservationStatus.COMPLETED
    }
}

private fun Reservation.markNoShow(now: LocalDateTime) {
    when (status) {
        ReservationStatus.PENDING,
        ReservationStatus.CONFIRMED ->
            if (now.isBefore(startTime)) {
                invalidTransition("Nie można oznaczyć nieobecności przed rozpoczęciem wizyty")
            }

        ReservationStatus.COMPLETED ->
            if (now.isAfter(endTime.plusHours(NO_SHOW_CORRECTION_HOURS))) {
                invalidTransition("Minął czas na oznaczenie nieobecności dla zakończonej wizyty")
            }

        else -> invalidTransition("Tej rezerwacji nie można oznaczyć jako nieobecność")
    }
    status = ReservationStatus.NO_SHOW
}
