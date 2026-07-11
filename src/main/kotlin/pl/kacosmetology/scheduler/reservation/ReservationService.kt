package pl.kacosmetology.scheduler.reservation

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.availability.EmployeeAvailabilityPolicy
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.company.effectivePrice
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.dto.DashboardReservationResponse
import pl.kacosmetology.scheduler.reservation.dto.toDashboardResponse
import pl.kacosmetology.scheduler.user.CompanyCustomerBlockRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import java.time.LocalDateTime
import java.time.ZoneId

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
    private val employeeAvailabilityPolicy: EmployeeAvailabilityPolicy
) {
    /**
     * Creates a new reservation with a price snapshot from the offering catalog.
     * Validates that the requested time slot is available and the customer is not blocked at the offering's company.
     * Throws [IllegalArgumentException] if the customer is blocked, the employee is not part of the offering's company,
     * has offering assignments but not for this offering, or [enforceAdvanceCheck] is true and the slot starts sooner
     * than the company's
     * [pl.kacosmetology.scheduler.company.Company.minBookingAdvanceMinutes] setting.
     *
     * @param enforceAdvanceCheck when false (staff bookings) the minimum-advance-time check is skipped.
     */
    @Transactional
    fun createReservation(
        customerId: Long,
        employeeId: Long,
        offeringId: Long,
        startTime: LocalDateTime,
        enforceAdvanceCheck: Boolean = true
    ): Reservation {
        if (!userRepository.existsById(customerId)) {
            throw NoSuchElementException("Klient nie istnieje")
        }

        val offering = offeringRepository.findById(offeringId)
            .orElseThrow { IllegalArgumentException("Usługa nie istnieje") }

        if (!offering.active) {
            throw IllegalArgumentException("Ta usługa nie jest już dostępna")
        }

        if (companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(offering.companyId, employeeId) == null) {
            throw IllegalArgumentException("Pracownik nie należy do firmy wybranej usługi")
        }

        if (assignmentRepository.existsByEmployeeId(employeeId) &&
            !assignmentRepository.existsByEmployeeIdAndOfferingId(employeeId, offeringId)
        ) {
            throw IllegalArgumentException("Ten pracownik nie wykonuje wybranej usługi")
        }

        val block = companyCustomerBlockRepository.findByCompanyIdAndCustomerId(offering.companyId, customerId)
        if (block?.blocked == true) {
            throw IllegalArgumentException("Klient jest zablokowany w tej firmie i nie może rezerwować online")
        }

        val company = companyRepository.findById(offering.companyId)
            .orElseThrow { NoSuchElementException("Firma nie istnieje") }

        if (enforceAdvanceCheck && company.minBookingAdvanceMinutes > 0) {
            val earliestAllowed =
                LocalDateTime.now(ZoneId.of("Europe/Warsaw")).plusMinutes(company.minBookingAdvanceMinutes.toLong())
            if (!startTime.isAfter(earliestAllowed)) {
                throw IllegalArgumentException(
                    "Rezerwację można złożyć co najmniej ${company.minBookingAdvanceMinutes} minut przed wizytą"
                )
            }
        }

        val endTime = startTime.plusMinutes(offering.durationMinutes.toLong())

        employeeAvailabilityPolicy.assertAvailable(offering.companyId, employeeId, startTime, endTime)

        val price = company.effectivePrice(offering.price, startTime)

        val saved = reservationRepository.save(
            Reservation(
                companyId = offering.companyId,
                customerId = customerId,
                employeeId = employeeId,
                serviceId = offeringId,
                price = price,
                startTime = startTime,
                endTime = endTime,
                status = ReservationStatus.PENDING
            )
        )
        applicationEventPublisher.publishEvent(ReservationCreatedEvent(saved))
        return saved
    }

    /** Cancels a reservation. Only the owning customer can cancel, and only if not already completed. */
    @Transactional
    fun cancelReservation(reservationId: Long, customerId: Long) {
        val reservation = reservationRepository.findById(reservationId)
            .orElseThrow { IllegalArgumentException("Rezerwacja nie istnieje") }

        if (reservation.customerId != customerId) {
            throw IllegalStateException("Nie możesz anulować nie swojej rezerwacji")
        }

        reservation.cancel()
        reservationRepository.save(reservation)
        applicationEventPublisher.publishEvent(ReservationCancelledEvent(reservation))
    }

    /** Marks a reservation as completed. Called by staff members. */
    @Transactional
    fun completeReservation(reservationId: Long, companyId: Long) {
        val reservation = reservationRepository.findById(reservationId)
            .orElseThrow { IllegalArgumentException("Rezerwacja nie istnieje") }

        if (reservation.companyId != companyId) {
            throw IllegalStateException("Brak dostępu do tej rezerwacji")
        }

        reservation.complete()
        reservationRepository.save(reservation)
    }

    /**
     * Marks a reservation as [ReservationStatus.NO_SHOW] and increments the customer's company-scoped no-show counter.
     * If the counter reaches the company's [pl.kacosmetology.scheduler.company.Company.maxNoShows] threshold
     * (and it is greater than zero), the customer is automatically blocked from online booking at that company.
     * Only PENDING or CONFIRMED reservations can be marked as no-show.
     */
    @Transactional
    fun markNoShow(reservationId: Long, companyId: Long) {
        val reservation = reservationRepository.findById(reservationId)
            .orElseThrow { NoSuchElementException("Rezerwacja nie istnieje") }

        if (reservation.companyId != companyId) {
            throw IllegalStateException("Brak dostępu do tej rezerwacji")
        }

        reservation.markNoShow()
        reservationRepository.save(reservation)

        companyCustomerBlockRepository.incrementNoShowCountAndApplyBlock(
            reservation.companyId,
            reservation.customerId
        )
    }

    /** Returns all reservations for a customer, ordered by start time descending. */
    @Transactional(readOnly = true)
    fun getCustomerReservations(customerId: Long): List<Reservation> {
        return reservationRepository.findAllByCustomerIdOrderByStartTimeDesc(customerId)
    }

    /**
     * Returns reservations for a given employee within a company and date range, enriched with customer names.
     * Used by the owner dashboard — the authorization check (OWNER can see any employee,
     * EMPLOYEE can only see their own) is performed in the controller before calling this method.
     */
    @Transactional(readOnly = true)
    fun getCompanyReservations(
        companyId: Long,
        employeeId: Long,
        start: LocalDateTime,
        end: LocalDateTime
    ): List<DashboardReservationResponse> {
        val reservations =
            reservationRepository.findByCompanyIdAndEmployeeIdAndDateRange(companyId, employeeId, start, end)
        val customerIds = reservations.map { it.customerId }.distinct()
        val usersById = userRepository.findAllById(customerIds).associateBy { it.id }
        return reservations.map { r ->
            val customer = usersById[r.customerId]
            r.toDashboardResponse(
                customerFirstName = customer?.firstName ?: "",
                customerLastName = customer?.lastName ?: ""
            )
        }
    }

    /** Returns an employee's schedule within a given time range. */
    @Transactional(readOnly = true)
    fun getEmployeeSchedule(employeeId: Long, start: LocalDateTime, end: LocalDateTime): List<Reservation> {
        return reservationRepository.findEmployeeSchedule(employeeId, start, end)
    }

    /**
     * Permanently deletes a reservation.
     * Only the owning company may delete; throws [IllegalStateException] (→409) on mismatch.
     */
    @Transactional
    fun deleteReservation(reservationId: Long, companyId: Long) {
        val reservation = reservationRepository.findById(reservationId)
            .orElseThrow { NoSuchElementException("Rezerwacja nie istnieje") }
        if (reservation.companyId != companyId) {
            throw IllegalStateException("Brak dostępu do tej rezerwacji")
        }
        reservationRepository.delete(reservation)
    }

    /**
     * Creates a reservation on behalf of a client, identified by phone number.
     * If no user with [customerPhone] exists, a new account is created using [customerFirstName] and [customerLastName].
     * Both name fields are required when the client does not exist yet.
     *
     * @param requesterCompanyId authenticated staff member's company; the selected offering must belong to that
     * company before any customer account is created.
     */
    @Transactional
    fun createReservationByStaff(
        employeeId: Long,
        serviceId: Long,
        startTime: LocalDateTime,
        customerPhone: String,
        customerFirstName: String?,
        customerLastName: String?,
        requesterCompanyId: Long
    ): Reservation {
        val offering = offeringRepository.findById(serviceId)
            .orElseThrow { IllegalArgumentException("Usługa nie istnieje") }
        if (offering.companyId != requesterCompanyId) {
            throw IllegalArgumentException("Usługa nie należy do firmy pracownika")
        }

        val customer = userRepository.findByPhoneNumber(customerPhone)
            ?: run {
                if (customerFirstName.isNullOrBlank() || customerLastName.isNullOrBlank()) {
                    throw IllegalArgumentException(
                        "Imię i nazwisko klienta są wymagane przy tworzeniu nowego konta"
                    )
                }
                userRepository.save(
                    User(
                        phoneNumber = customerPhone,
                        firstName = customerFirstName,
                        lastName = customerLastName
                    )
                )
            }

        return createReservation(
            customerId = requireNotNull(customer.id) { "Persisted customer must have an ID" },
            employeeId = employeeId,
            offeringId = serviceId,
            startTime = startTime,
            enforceAdvanceCheck = false
        )
    }
}

private fun Reservation.cancel() {
    when (status) {
        ReservationStatus.CANCELLED -> error("Rezerwacja jest już anulowana")
        ReservationStatus.COMPLETED -> error("Nie można anulować wizyty, która już się odbyła")
        ReservationStatus.NO_SHOW -> error("Nie można anulować rezerwacji oznaczonej jako nieobecność")
        else -> status = ReservationStatus.CANCELLED
    }
}

private fun Reservation.complete() {
    when (status) {
        ReservationStatus.CANCELLED -> error("Nie można zakończyć odwołanej wizyty")
        ReservationStatus.COMPLETED -> error("Wizyta jest już zakończona")
        ReservationStatus.NO_SHOW -> error("Nie można zakończyć wizyty oznaczonej jako nieobecność")
        else -> status = ReservationStatus.COMPLETED
    }
}

private fun Reservation.markNoShow() {
    when (status) {
        ReservationStatus.PENDING, ReservationStatus.CONFIRMED -> status = ReservationStatus.NO_SHOW
        else -> error("Tylko aktywna rezerwacja może być oznaczona jako nieobecność")
    }
}
