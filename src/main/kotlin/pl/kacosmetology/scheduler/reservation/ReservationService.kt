package pl.kacosmetology.scheduler.reservation

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.availability.BookableSlotPolicy
import pl.kacosmetology.scheduler.availability.EmployeeAvailabilityPolicy
import pl.kacosmetology.scheduler.company.*
import pl.kacosmetology.scheduler.config.*
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.quote.QuotePolicy
import pl.kacosmetology.scheduler.reservation.dto.*
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.user.*

/** Employee outcome corrections use a 24 elapsed-hour deadline; owners have no late deadline. */
const val NO_SHOW_CORRECTION_HOURS = 24L

/** Transactional booking and lifecycle coordinator; company locks serialize allocation, customer caps and policy. */
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
    private val companyCustomers: CompanyCustomerRepository,
    private val quotes: QuotePolicy,
    private val audit: ReservationAuditContext,
    private val jdbc: JdbcTemplate,
) {
    /**
     * Creates a customer booking under the salon lock; a successful submission is replayed by BookingSubmissionService.
     */
    @Transactional
    fun createReservation(
        customerId: UUID,
        employeeId: UUID?,
        offeringId: UUID,
        startTime: LocalDateTime,
        enforceAdvanceCheck: Boolean = true,
        enforceSlotGrid: Boolean = true,
        firstName: String? = null,
        lastName: String? = null,
        contactEmail: String? = null,
        quoteId: UUID? = null,
    ): BookingResult {
        val companyId = offeringCompany(offeringId)
        val company = lockCompany(companyId)
        if (!userRepository.existsById(customerId)) missingCustomer()
        if (employeeId != null) checkEmployee(companyId, employeeId, offeringId)
        else
            companyEmployeeRepository
                .findAllByCompanyId(companyId)
                .sortedBy { it.userId }
                .forEach {
                    companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, it.userId)
                }
        val authoritativeNow = clock.instant()
        val now = LocalDateTime.ofInstant(authoritativeNow, BUSINESS_ZONE)
        val agreement = quotes.resolve(customerId, offeringId, startTime, quoteId, authoritativeNow)
        val end = startTime.plusMinutes(agreement.durationMinutes.toLong())
        validateBusinessInterval(startTime, end)
        if (!startTime.isAfter(now))
            conflict(ErrorCode.BOOKING_ADVANCE_REQUIRED, "Wizyta musi rozpoczynać się w przyszłości")
        if (enforceAdvanceCheck) checkCustomerTime(company, startTime, now)
        checkBlocked(companyId, customerId, startTime, now)
        if (
            reservationRepository.countFutureCustomerBookings(companyId, customerId, now) >=
                company.maxFutureCustomerBookings
        ) {
            conflict(ErrorCode.BOOKING_LIMIT_REACHED, "Osiągnięto limit przyszłych rezerwacji w tym salonie")
        }
        checkCustomerConflict(companyId, customerId, startTime, end)
        val selected = employeeId ?: allocate(company, offeringId, startTime, end, agreement.durationMinutes)
        checkEmployee(companyId, selected, offeringId)
        if (
            enforceSlotGrid &&
                !bookableSlotPolicy.isOnGrid(
                    companyId,
                    selected,
                    startTime,
                    agreement.durationMinutes,
                    company.slotIntervalMinutes,
                )
        ) {
            slotUnavailable()
        }
        checkEmployeeConflict(companyId, selected, startTime, end)
        ensureProfile(companyId, customerId, firstName, lastName, contactEmail)
        audit.capture()
        val saved =
            reservationRepository.saveAndFlush(
                Reservation(
                    companyId = companyId,
                    customerId = customerId,
                    employeeId = selected,
                    serviceId = offeringId,
                    price = agreement.price,
                    startTime = startTime,
                    endTime = end,
                    status = ReservationStatus.BOOKED,
                    acceptedServiceName = agreement.serviceName,
                    agreedDurationMinutes = agreement.durationMinutes,
                    creationSource = "CUSTOMER",
                ),
            )
        return BookingResult(saved, true)
    }

    /**
     * Staff may book ongoing visits, off-grid and outside schedules; hard conflicts and future-customer blocks remain.
     */
    @Transactional
    fun createReservationByStaff(
        employeeId: UUID,
        serviceId: UUID,
        startTime: LocalDateTime,
        customerPhone: String,
        customerFirstName: String?,
        customerLastName: String?,
        requesterCompanyId: UUID,
        price: Int? = null,
        durationMinutes: Int? = null,
        overrideEmployeeBlock: Boolean = false,
        owner: Boolean = false,
        notifyCustomer: Boolean = false,
    ): BookingResult {
        lockCompany(requesterCompanyId)
        val offering = offeringRepository.findByIdAndCompanyId(serviceId, requesterCompanyId) ?: missingReservation()
        if (!offering.active) conflict(ErrorCode.OFFERING_UNAVAILABLE, "Usługa jest nieaktywna")
        val duration = durationMinutes ?: offering.durationMinutes
        val end = startTime.plusMinutes(duration.toLong())
        validateBusinessInterval(startTime, end)
        checkEmployee(requesterCompanyId, employeeId, serviceId)
        val now = LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE)
        if (!end.isAfter(now)) invalidTransition("Wizyta musi kończyć się w przyszłości")
        checkEmployeeConflict(
            requesterCompanyId,
            employeeId,
            startTime,
            end,
            overrideBlock = overrideEmployeeBlock,
            owner = owner,
        )
        val phone = canonicalPhone(customerPhone)
        val customer =
            userRepository.findByPhoneNumber(phone)
                ?: run {
                    userRepository.insertIfPhoneAbsent(phone, "", "")
                    checkNotNull(userRepository.findByPhoneNumber(phone))
                }
        val customerId = requireNotNull(customer.id)
        checkBlocked(requesterCompanyId, customerId, startTime, now)
        checkCustomerConflict(requesterCompanyId, customerId, startTime, end)
        ensureProfile(requesterCompanyId, customerId, customerFirstName, customerLastName, null)
        audit.capture()
        val saved =
            reservationRepository.saveAndFlush(
                Reservation(
                    companyId = requesterCompanyId,
                    customerId = customerId,
                    employeeId = employeeId,
                    serviceId = serviceId,
                    price = price ?: offering.price,
                    startTime = startTime,
                    endTime = end,
                    acceptedServiceName = offering.name,
                    agreedDurationMinutes = duration,
                    creationSource = "STAFF",
                ),
            )
        notify(saved, "CREATE", notifyCustomer)
        return BookingResult(saved, true)
    }

    /** Edits the same row and original creation time, retaining commercial terms for an unchanged service. */
    @Transactional
    fun editReservation(
        id: UUID,
        companyId: UUID,
        revision: Long,
        request: EditReservationRequest,
        owner: Boolean,
    ): Reservation {
        lockCompany(companyId)
        val reservation = locked(id, companyId)
        checkRevision(reservation, revision)
        companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(
            companyId,
            request.employeeId ?: reservation.employeeId,
        )
        val now = LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE)
        if (reservation.archived || reservation.status != ReservationStatus.BOOKED || !reservation.endTime.isAfter(now))
            invalidTransition("Można edytować tylko trwającą lub przyszłą zarezerwowaną wizytę")
        val serviceId = request.serviceId ?: reservation.serviceId
        val offering = offeringRepository.findByIdAndCompanyId(serviceId, companyId) ?: missingReservation()
        val changedService = serviceId != reservation.serviceId
        if (changedService && !offering.active) conflict(ErrorCode.OFFERING_UNAVAILABLE, "Usługa jest nieaktywna")
        val start = request.startTime ?: reservation.startTime
        val duration =
            request.durationMinutes
                ?: if (changedService) offering.durationMinutes else reservation.agreedDurationMinutes
        val price = request.price ?: if (changedService) offering.price else reservation.price
        val name = if (changedService) offering.name else reservation.acceptedServiceName
        val end = start.plusMinutes(duration.toLong())
        validateBusinessInterval(start, end)
        if (!end.isAfter(now)) invalidTransition("Wizyta musi kończyć się w przyszłości")
        checkBlocked(companyId, reservation.customerId, start, now)
        if (isBlocked(companyId, reservation.customerId) && start != reservation.startTime)
            conflict(
                ErrorCode.CUSTOMER_BLOCKED,
                "Zablokowanego klienta można obsłużyć wyłącznie w pierwotnym trwającym terminie",
            )
        val employeeId = request.employeeId ?: reservation.employeeId
        checkEmployee(companyId, employeeId, serviceId)
        checkEmployeeConflict(companyId, employeeId, start, end, id, request.overrideEmployeeBlock, owner)
        checkCustomerConflict(companyId, reservation.customerId, start, end, id)
        val changed =
            employeeId != reservation.employeeId ||
                serviceId != reservation.serviceId ||
                start != reservation.startTime ||
                end != reservation.endTime ||
                price != reservation.price ||
                name != reservation.acceptedServiceName
        if (changed) {
            audit.capture()
            reservation.employeeId = employeeId
            reservation.serviceId = serviceId
            reservation.startTime = start
            reservation.endTime = end
            reservation.price = price
            reservation.agreedDurationMinutes = duration
            reservation.acceptedServiceName = name
            if (changedService) reservation.serviceNameSource = "ACCEPTED"
            reservationRepository.saveAndFlush(reservation)
            notify(reservation, "EDIT", request.notifyCustomer)
        }
        return reservation
    }

    /** Customer cancellation is allowed strictly before start and never exposes a private staff reason. */
    @Transactional
    fun cancelReservation(reservationId: UUID, customerId: UUID, revision: Long? = null, reason: String? = null) {
        val companyId =
            jdbc
                .queryForList(
                    "SELECT company_id FROM reservations WHERE id=? AND customer_id=?",
                    UUID::class.java,
                    reservationId,
                    customerId,
                )
                .firstOrNull() ?: missingReservation()
        transition(
            reservationId,
            companyId,
            ReservationStatus.CANCELLED,
            revision,
            ReservationActionRequest(reason),
            customerId = customerId,
        )
    }

    /**
     * Staff cancellation requires a private reason and may cancel booked or completed visits, including elapsed ones.
     */
    @Transactional
    fun cancelReservationByStaff(
        reservationId: UUID,
        companyId: UUID,
        revision: Long? = null,
        request: ReservationActionRequest = ReservationActionRequest(),
        owner: Boolean = false,
    ) {
        transition(reservationId, companyId, ReservationStatus.CANCELLED, revision, request, owner)
    }

    /** Staff completion begins at scheduled start. */
    @Transactional
    fun completeReservation(
        reservationId: UUID,
        companyId: UUID,
        revision: Long? = null,
        request: ReservationActionRequest = ReservationActionRequest(),
        owner: Boolean = false,
    ) {
        transition(reservationId, companyId, ReservationStatus.COMPLETED, revision, request, owner)
    }

    /** Marks a counted no-show; owner corrections are unlimited and employee corrections have an elapsed deadline. */
    @Transactional
    fun markNoShow(
        reservationId: UUID,
        companyId: UUID,
        revision: Long? = null,
        request: ReservationActionRequest = ReservationActionRequest(),
        owner: Boolean = false,
    ) {
        transition(reservationId, companyId, ReservationStatus.NO_SHOW, revision, request, owner)
    }

    /** Restores a cancelled visit or corrects a no-show to BOOKED, preserving the agreement and identity. */
    @Transactional
    fun restoreReservation(
        id: UUID,
        companyId: UUID,
        revision: Long,
        request: ReservationActionRequest,
        owner: Boolean,
    ) {
        transition(id, companyId, ReservationStatus.BOOKED, revision, request, owner)
    }

    /**
     * Archive/unarchive retains all data, submission identity and durable history; only cancelled rows can be archived.
     */
    @Transactional
    fun archiveReservation(id: UUID, companyId: UUID, revision: Long, archived: Boolean) {
        lockCompany(companyId)
        val reservation = locked(id, companyId)
        checkRevision(reservation, revision)
        if (archived && reservation.status != ReservationStatus.CANCELLED)
            invalidTransition("Archiwizować można tylko anulowaną wizytę")
        if (reservation.archived != archived) {
            audit.capture()
            reservation.archived = archived
            reservationRepository.saveAndFlush(reservation)
        }
    }

    /** Returns normal customer history, excluding archived appointments. */
    @Transactional(readOnly = true)
    fun getCustomerReservations(customerId: UUID): List<Reservation> =
        reservationRepository.findAllByCustomerIdAndArchivedFalseOrderByStartTimeDesc(customerId)

    /** Both staff roles can read company visit history with local customer names. */
    @Transactional(readOnly = true)
    fun getCompanyReservations(
        companyId: UUID,
        employeeId: UUID,
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<DashboardReservationResponse> {
        if (!companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId)) missingReservation()
        return reservationRepository.findByCompanyIdAndEmployeeIdAndDateRange(companyId, employeeId, start, end).map {
            reservation ->
            val profile = companyCustomers.findByCompanyIdAndUserId(companyId, reservation.customerId)
            reservation.toDashboardResponse(profile?.firstName ?: "", profile?.lastName ?: "")
        }
    }

    /** Reads a company employee calendar without archived rows. */
    @Transactional(readOnly = true)
    fun getEmployeeSchedule(
        companyId: UUID,
        employeeId: UUID,
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<Reservation> = reservationRepository.findEmployeeSchedule(companyId, employeeId, start, end)

    /** Response combines historical commercial values with current company and employee presentation. */
    @Transactional(readOnly = true)
    fun response(reservation: Reservation): ReservationResponse {
        val company = companyRepository.findById(reservation.companyId).orElseThrow()
        val employee =
            companyEmployeeRepository.findAllByCompanyId(reservation.companyId).first {
                it.userId == reservation.employeeId
            }
        return reservation
            .toResponse()
            .copy(
                companyId = reservation.companyId,
                companyName = company.name,
                companyAddress = company.address,
                serviceName = reservation.acceptedServiceName,
                employeeName = "${employee.firstName} ${employee.lastName}",
            )
    }

    /** Audited automatic completion catches up after restart and retains the full occupied interval. */
    @Transactional
    fun autoCompleteElapsed(): Int {
        var completed = 0
        for (candidate in
            reservationRepository.findElapsedBookings(LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE))) {
            lockCompany(candidate.companyId)
            val reservation = locked(requireNotNull(candidate.id), candidate.companyId)
            if (
                reservation.status == ReservationStatus.BOOKED &&
                    !reservation.endTime.isAfter(LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE))
            ) {
                audit.capture()
                reservation.status = ReservationStatus.COMPLETED
                reservationRepository.saveAndFlush(reservation)
                completed++
            }
        }
        return completed
    }

    private fun transition(
        id: UUID,
        companyId: UUID,
        target: ReservationStatus,
        revision: Long?,
        request: ReservationActionRequest,
        owner: Boolean = false,
        customerId: UUID? = null,
    ) {
        lockCompany(companyId)
        val reservation = locked(id, companyId)
        if (customerId != null && reservation.customerId != customerId) missingReservation()
        val expected = revision ?: reservation.version
        val account =
            (SecurityContextHolder.getContext().authentication?.principal as? CustomUserDetails)?.accountKey()
                ?: if (customerId != null) "customer:$customerId" else "system"
        val command = "$target:$expected"
        val fingerprint =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(
                    listOf(
                            request.reason,
                            request.customerExplanation,
                            request.notifyCustomer,
                            request.overrideEmployeeBlock,
                        )
                        .toString()
                        .toByteArray(),
                )
                .joinToString("") { "%02x".format(it) }
        val previous =
            jdbc
                .queryForList(
                    "SELECT * FROM reservation_commands WHERE reservation_id=? AND account_key=? AND command_key=?",
                    id,
                    account,
                    command,
                )
                .firstOrNull()
        if (previous != null) {
            val reversed =
                jdbc.queryForObject(
                    "SELECT count(*) FROM reservation_audit WHERE reservation_id=? AND (after_state->>'version')::bigint > ? AND after_state->>'status' <> ?",
                    Long::class.java,
                    id,
                    previous["outcome_revision"],
                    target.name,
                )!! > 0
            if (previous["request_hash"] == fingerprint && reservation.status == target && !reversed) return
            invalidTransition("Nieaktualne polecenie po zmianie wyniku wizyty")
        }
        checkRevision(reservation, expected)
        if (reservation.archived) invalidTransition("Najpierw przywróć wizytę z archiwum")
        companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, reservation.employeeId)
        val now = LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE)
        val original = reservation.status
        if (original == target) invalidTransition("Wynik wizyty został już zmieniony innym poleceniem")
        if (original == ReservationStatus.NO_SHOW) correctionDeadline(reservation, owner)
        when (target) {
            ReservationStatus.CANCELLED -> {
                if (customerId != null) {
                    if (original != ReservationStatus.BOOKED || !now.isBefore(reservation.startTime))
                        invalidTransition("Możesz anulować własną wizytę wyłącznie przed rozpoczęciem")
                    reservation.customerExplanation = request.reason
                } else {
                    if (request.reason.isNullOrBlank())
                        throw ApiException(ErrorCode.VALIDATION_FAILED, "Wewnętrzny powód anulowania jest wymagany")
                    if (
                        original !in
                            setOf(ReservationStatus.BOOKED, ReservationStatus.COMPLETED, ReservationStatus.NO_SHOW)
                    )
                        invalidTransition("Nie można anulować wizyty")
                    reservation.internalCancellationReason = request.reason
                    reservation.customerExplanation = request.customerExplanation
                }
            }
            ReservationStatus.COMPLETED ->
                if (
                    original !in setOf(ReservationStatus.BOOKED, ReservationStatus.NO_SHOW) ||
                        now.isBefore(reservation.startTime)
                )
                    invalidTransition("Wizytę można zakończyć od zaplanowanego rozpoczęcia")
            ReservationStatus.NO_SHOW -> {
                if (
                    original !in setOf(ReservationStatus.BOOKED, ReservationStatus.COMPLETED) ||
                        now.isBefore(reservation.startTime)
                )
                    invalidTransition("Nieobecność można oznaczyć od zaplanowanego rozpoczęcia")
                correctionDeadline(reservation, owner)
            }
            ReservationStatus.BOOKED -> {
                if (
                    original !in setOf(ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW) ||
                        !reservation.endTime.isAfter(now)
                )
                    invalidTransition("Przywrócić można tylko przyszłą lub trwającą wizytę")
                if (original == ReservationStatus.NO_SHOW && now.isBefore(reservation.startTime))
                    invalidTransition("Nieobecność można przywrócić tylko w trakcie wizyty")
                val offering =
                    offeringRepository.findByIdAndCompanyId(reservation.serviceId, companyId) ?: missingReservation()
                if (!offering.active) conflict(ErrorCode.OFFERING_UNAVAILABLE, "Usługa jest nieaktywna")
                checkEmployee(companyId, reservation.employeeId, reservation.serviceId)
                if (
                    reservation.creationSource != "STAFF" &&
                        !bookableSlotPolicy.isOnGrid(
                            companyId,
                            reservation.employeeId,
                            reservation.startTime,
                            reservation.agreedDurationMinutes,
                            companyRepository.findById(companyId).orElseThrow().slotIntervalMinutes,
                        )
                )
                    slotUnavailable()
                checkBlocked(companyId, reservation.customerId, reservation.startTime, now)
                checkEmployeeConflict(
                    companyId,
                    reservation.employeeId,
                    reservation.startTime,
                    reservation.endTime,
                    id,
                    request.overrideEmployeeBlock,
                    owner,
                )
                checkCustomerConflict(companyId, reservation.customerId, reservation.startTime, reservation.endTime, id)
            }
        }
        if (original == ReservationStatus.NO_SHOW && target == ReservationStatus.COMPLETED) {
            checkEmployeeConflict(
                companyId,
                reservation.employeeId,
                reservation.startTime,
                reservation.endTime,
                id,
                request.overrideEmployeeBlock,
                owner,
            )
            checkCustomerConflict(companyId, reservation.customerId, reservation.startTime, reservation.endTime, id)
        }
        audit.capture()
        reservation.outcomeExplanation = request.reason
        reservation.status = target
        reservationRepository.saveAndFlush(reservation)
        if (target == ReservationStatus.NO_SHOW) applyPenalty(reservation)
        if (original == ReservationStatus.NO_SHOW) removePenalty(reservation)
        jdbc.update(
            "INSERT INTO reservation_commands(reservation_id,account_key,command_key,request_hash,outcome_revision,outcome_status) VALUES(?,?,?,?,?,?)",
            id,
            account,
            command,
            fingerprint,
            reservation.version,
            target.name,
        )
        if (customerId == null && target in setOf(ReservationStatus.CANCELLED, ReservationStatus.BOOKED))
            notify(reservation, if (target == ReservationStatus.BOOKED) "RESTORE" else "CANCEL", request.notifyCustomer)
    }

    private fun correctionDeadline(reservation: Reservation, owner: Boolean) {
        if (
            !owner &&
                clock.instant().isAfter(reservation.endTime.atZone(BUSINESS_ZONE).toInstant().plusSeconds(24 * 3600))
        )
            invalidTransition("Minął termin korekty wyniku wizyty")
    }

    private fun offeringCompany(id: UUID): UUID =
        jdbc.queryForList("SELECT company_id FROM offerings WHERE id=?", UUID::class.java, id).firstOrNull()
            ?: missingReservation()

    private fun lockCompany(id: UUID): Company = companyRepository.findByIdForUpdate(id) ?: missingReservation()

    private fun locked(id: UUID, company: UUID): Reservation =
        reservationRepository.findByIdAndCompanyIdForUpdate(id, company) ?: missingReservation()

    private fun checkRevision(reservation: Reservation, expected: Long) {
        if (reservation.version != expected)
            conflict(ErrorCode.CONCURRENT_MODIFICATION, "Dane wizyty zmieniły się; odśwież widok")
    }

    private fun checkEmployee(companyId: UUID, employeeId: UUID, serviceId: UUID): CompanyEmployee {
        val employee =
            companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, employeeId) ?: missingReservation()
        if (
            !employee.enabled ||
                (employee.eligibilityMode == EmployeeEligibilityMode.SPECIFIC_SERVICES &&
                    !assignmentRepository.existsByCompanyIdAndEmployeeIdAndOfferingId(companyId, employeeId, serviceId))
        ) {
            conflict(ErrorCode.EMPLOYEE_OFFERING_NOT_ASSIGNED, "Pracownik nie jest dostępny dla tej usługi")
        }
        return employee
    }

    private fun checkCustomerTime(company: Company, start: LocalDateTime, now: LocalDateTime) {
        val earliest = clock.instant().plusSeconds(company.minBookingAdvanceMinutes * 60L)
        if (company.minBookingAdvanceMinutes > 0 && start.atZone(BUSINESS_ZONE).toInstant().isBefore(earliest))
            conflict(ErrorCode.BOOKING_ADVANCE_REQUIRED, "Nie zachowano minimalnego wyprzedzenia")
        if (start.toLocalDate().isAfter(now.toLocalDate().plusDays(company.maxBookingAdvanceDays.toLong())))
            conflict(ErrorCode.BOOKING_WINDOW_EXCEEDED, "Termin wykracza poza horyzont rezerwacji")
    }

    private fun isBlocked(companyId: UUID, customerId: UUID) =
        companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId)?.blocked == true

    private fun checkBlocked(companyId: UUID, customerId: UUID, start: LocalDateTime, now: LocalDateTime) {
        if (start.isAfter(now) && isBlocked(companyId, customerId))
            conflict(ErrorCode.CUSTOMER_BLOCKED, "Klient jest zablokowany w tym salonie")
    }

    private fun ensureProfile(companyId: UUID, customerId: UUID, first: String?, last: String?, email: String?) {
        if (companyCustomers.findByCompanyIdAndUserId(companyId, customerId) != null) return
        if (first.isNullOrBlank() || last.isNullOrBlank())
            throw ApiException(
                ErrorCode.CUSTOMER_NAME_REQUIRED,
                "Pierwsza rezerwacja wymaga imienia i nazwiska klienta",
            )
        companyCustomers.save(
            CompanyCustomer(
                companyId = companyId,
                userId = customerId,
                firstName = first.trim(),
                lastName = last.trim(),
                contactEmail = email,
            ),
        )
    }

    private fun checkCustomerConflict(
        companyId: UUID,
        customerId: UUID,
        start: LocalDateTime,
        end: LocalDateTime,
        exclude: UUID = UUID(0, 0),
    ) {
        if (reservationRepository.existsCustomerOverlapping(companyId, customerId, start, end, exclude))
            slotUnavailable()
    }

    private fun checkEmployeeConflict(
        companyId: UUID,
        employeeId: UUID,
        start: LocalDateTime,
        end: LocalDateTime,
        exclude: UUID = UUID(0, 0),
        overrideBlock: Boolean = false,
        owner: Boolean = false,
    ) {
        if (overrideBlock && !owner)
            throw ApiException(ErrorCode.ACCESS_DENIED, "Tylko właściciel może pominąć blokadę pracownika")
        val occupied =
            jdbc.queryForObject(
                "SELECT count(*) FROM reservations WHERE company_id=? AND employee_id=? AND id<>? AND status NOT IN ('CANCELLED','NO_SHOW') AND start_time<? AND end_time>?",
                Long::class.java,
                companyId,
                employeeId,
                exclude,
                sqlTime(end),
                sqlTime(start),
            )!! > 0
        val blocked =
            !overrideBlock &&
                jdbc.queryForObject(
                    "SELECT count(*) FROM schedule_blocks WHERE company_id=? AND employee_id=? AND start_time<? AND end_time>?",
                    Long::class.java,
                    companyId,
                    employeeId,
                    sqlTime(end),
                    sqlTime(start),
                )!! > 0
        if (occupied || blocked) slotUnavailable()
    }

    private fun allocate(
        company: Company,
        serviceId: UUID,
        start: LocalDateTime,
        end: LocalDateTime,
        duration: Int,
    ): UUID {
        val options =
            companyEmployeeRepository
                .findAllByCompanyId(requireNotNull(company.id))
                .filter { employee ->
                    employee.enabled &&
                        (employee.eligibilityMode == EmployeeEligibilityMode.ALL_SERVICES ||
                            assignmentRepository.existsByCompanyIdAndEmployeeIdAndOfferingId(
                                company.id!!,
                                employee.userId,
                                serviceId,
                            )) &&
                        bookableSlotPolicy.isOnGrid(
                            company.id!!,
                            employee.userId,
                            start,
                            duration,
                            company.slotIntervalMinutes,
                        ) &&
                        employeeAvailabilityPolicy.findFirstConflict(company.id!!, employee.userId, start, end) == null
                }
                .map { employee ->
                    val shift = bookableSlotPolicy.effectiveShift(company.id!!, employee.userId, start.toLocalDate())!!
                    val denominator = shift.durationMinutes
                    val minutes =
                        jdbc.queryForObject(
                            "SELECT coalesce(sum(extract(epoch FROM (least(end_time,?) - greatest(start_time,?))))/60,0) FROM reservations WHERE company_id=? AND employee_id=? AND status IN ('BOOKED','COMPLETED') AND start_time<? AND end_time>?",
                            Double::class.java,
                            sqlTime(start.toLocalDate().plusDays(1).atStartOfDay()),
                            sqlTime(start.toLocalDate().atStartOfDay()),
                            company.id,
                            employee.userId,
                            sqlTime(start.toLocalDate().plusDays(1).atStartOfDay()),
                            sqlTime(start.toLocalDate().atStartOfDay()),
                        )!!
                    employee.userId to (minutes / denominator)
                }
        val minimum = options.minOfOrNull { it.second } ?: slotUnavailable()
        val tied = options.filter { it.second == minimum }
        return tied[ThreadLocalRandom.current().nextInt(tied.size)].first
    }

    private fun applyPenalty(reservation: Reservation) {
        jdbc.update(
            "INSERT INTO no_show_penalties(reservation_id,company_id,customer_id,appointment_date) VALUES(?,?,?,?) ON CONFLICT(reservation_id) DO UPDATE SET active=true,appointment_date=EXCLUDED.appointment_date",
            reservation.id,
            reservation.companyId,
            reservation.customerId,
            reservation.startTime.toLocalDate(),
        )
        val count = noShowCount(reservation.companyId, reservation.customerId)
        val threshold = companyRepository.findById(reservation.companyId).orElseThrow().maxNoShows
        val today = LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE)
        val appointmentDate = reservation.startTime.toLocalDate()
        val shouldBlock =
            threshold > 0 &&
                count >= threshold &&
                !appointmentDate.isBefore(today.minusYears(1)) &&
                !appointmentDate.isAfter(today)
        jdbc.update(
            "INSERT INTO company_customer_blocks(company_id,customer_id,no_show_count,blocked,automatic_blocked) VALUES(?,?,?,?,?) ON CONFLICT(company_id,customer_id) DO UPDATE SET no_show_count=EXCLUDED.no_show_count,automatic_blocked=company_customer_blocks.automatic_blocked OR EXCLUDED.automatic_blocked,blocked=company_customer_blocks.manual_blocked OR company_customer_blocks.automatic_blocked OR EXCLUDED.automatic_blocked",
            reservation.companyId,
            reservation.customerId,
            count,
            shouldBlock,
            shouldBlock,
        )
        if (shouldBlock) cancelFutureForBlock(reservation.companyId, reservation.customerId)
    }

    private fun removePenalty(reservation: Reservation) {
        jdbc.update("UPDATE no_show_penalties SET active=false WHERE reservation_id=?", reservation.id)
        val count = noShowCount(reservation.companyId, reservation.customerId)
        val threshold = companyRepository.findById(reservation.companyId).orElseThrow().maxNoShows
        jdbc.update(
            "UPDATE company_customer_blocks SET no_show_count=?,automatic_blocked=CASE WHEN ?=0 OR ?<? THEN false ELSE automatic_blocked END,blocked=manual_blocked OR (automatic_blocked AND ?>0 AND ?>=?) WHERE company_id=? AND customer_id=?",
            count,
            threshold,
            count,
            threshold,
            threshold,
            count,
            threshold,
            reservation.companyId,
            reservation.customerId,
        )
    }

    /** Current penalty count uses an inclusive rolling Warsaw calendar year. */
    fun noShowCount(companyId: UUID, customerId: UUID): Int {
        val today = LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE)
        return jdbc.queryForObject(
            "SELECT count(*) FROM no_show_penalties WHERE company_id=? AND customer_id=? AND active AND appointment_date BETWEEN ? AND ?",
            Int::class.java,
            companyId,
            customerId,
            today.minusYears(1),
            today,
        )!!
    }

    /** Cancels only future booked visits; callers hold the company lock and preserve ongoing appointments. */
    fun cancelFutureForBlock(companyId: UUID, customerId: UUID) {
        val future =
            reservationRepository.findAllByCustomerIdAndArchivedFalseOrderByStartTimeDesc(customerId).filter {
                it.companyId == companyId &&
                    it.status == ReservationStatus.BOOKED &&
                    it.startTime.isAfter(LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE))
            }
        for (reservation in future) {
            audit.capture()
            reservation.status = ReservationStatus.CANCELLED
            reservation.internalCancellationReason = "Blokada klienta w salonie"
            reservationRepository.saveAndFlush(reservation)
        }
        if (future.isNotEmpty())
            applicationEventPublisher.publishEvent(
                CustomerBlockedBookingsEvent(companyId, customerId, future.map { requireNotNull(it.id) }),
            )
    }

    private fun notify(reservation: Reservation, kind: String, requested: Boolean) {
        applicationEventPublisher.publishEvent(ReservationChangedEvent(requireNotNull(reservation.id), kind, requested))
    }

    private fun canonicalPhone(phone: String): String {
        val digits = phone.trim().removePrefix("+")
        require(digits.matches(Regex("[0-9]{9,15}"))) { "Nieprawidłowy numer telefonu" }
        return "+$digits"
    }

    private fun sqlTime(time: LocalDateTime) = time.atZone(BUSINESS_ZONE).toOffsetDateTime()

    private fun missingCustomer(): Nothing = throw ApiException(ErrorCode.CUSTOMER_NOT_FOUND, "Klient nie istnieje")

    private fun missingReservation(): Nothing =
        throw ApiException(ErrorCode.RESERVATION_NOT_FOUND, "Zasób nie istnieje")

    private fun slotUnavailable(): Nothing =
        conflict(ErrorCode.SLOT_UNAVAILABLE, "Ten termin jest już zajęty lub niedostępny")

    private fun invalidTransition(message: String): Nothing = conflict(ErrorCode.INVALID_STATUS_TRANSITION, message)

    private fun conflict(code: ErrorCode, message: String): Nothing = throw ApiException(code, message)
}
