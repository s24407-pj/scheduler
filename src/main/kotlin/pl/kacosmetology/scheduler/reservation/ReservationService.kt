package pl.kacosmetology.scheduler.reservation

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.notification.NotificationService
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.user.CompanyCustomerBlock
import pl.kacosmetology.scheduler.user.CompanyCustomerBlockRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import java.time.LocalDateTime

/** Core business logic for reservation lifecycle: create, cancel, complete, and no-show marking. */
@Service
class ReservationService(
    private val reservationRepository: ReservationRepository,
    private val offeringRepository: OfferingRepository,
    private val userRepository: UserRepository,
    private val assignmentRepository: EmployeeOfferingAssignmentRepository,
    private val companyRepository: CompanyRepository,
    private val notificationService: NotificationService,
    private val companyCustomerBlockRepository: CompanyCustomerBlockRepository
) {
    private val logger = LoggerFactory.getLogger(ReservationService::class.java)

    /**
     * Creates a new reservation with a price snapshot from the offering catalog.
     * Validates that the requested time slot is available and the customer is not blocked at the offering's company.
     * Throws [IllegalArgumentException] if the customer is blocked or the employee has offering assignments but not for this offering.
     */
    @Transactional
    fun createReservation(
        customerId: Long,
        employeeId: Long,
        offeringId: Long,
        startTime: LocalDateTime
    ): Reservation {
        if (!userRepository.existsById(customerId)) {
            throw NoSuchElementException("Klient nie istnieje")
        }

        val offering = offeringRepository.findById(offeringId)
            .orElseThrow { IllegalArgumentException("Usługa nie istnieje") }

        if (!offering.active) {
            throw IllegalArgumentException("Ta usługa nie jest już dostępna")
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

        val endTime = startTime.plusMinutes(offering.durationMinutes.toLong())

        if (reservationRepository.existsOverlapping(employeeId, startTime, endTime)) {
            throw IllegalStateException("Ten termin jest już zajęty")
        }

        val saved = reservationRepository.save(
            Reservation(
                companyId = offering.companyId,
                customerId = customerId,
                employeeId = employeeId,
                serviceId = offeringId,
                price = offering.price,
                startTime = startTime,
                endTime = endTime,
                status = ReservationStatus.PENDING
            )
        )
        try {
            notificationService.sendBookingConfirmation(saved)
        } catch (e: Exception) {
            logger.warn("Failed to send booking confirmation for reservation ${saved.id}", e)
        }
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

        if (reservation.status == ReservationStatus.CANCELLED) {
            throw IllegalStateException("Rezerwacja jest już anulowana")
        }

        if (reservation.status == ReservationStatus.COMPLETED) {
            throw IllegalStateException("Nie można anulować wizyty, która już się odbyła")
        }

        reservation.status = ReservationStatus.CANCELLED
        reservationRepository.save(reservation)
        try {
            notificationService.sendCancellationNotification(reservation)
        } catch (e: Exception) {
            logger.warn("Failed to send cancellation notification for reservation ${reservation.id}", e)
        }
    }

    /** Marks a reservation as completed. Called by staff members. */
    @Transactional
    fun completeReservation(reservationId: Long) {
        val reservation = reservationRepository.findById(reservationId)
            .orElseThrow { IllegalArgumentException("Rezerwacja nie istnieje") }

        if (reservation.status == ReservationStatus.CANCELLED) {
            throw IllegalStateException("Nie można zakończyć odwołanej wizyty")
        }

        if (reservation.status == ReservationStatus.COMPLETED) {
            throw IllegalStateException("Wizyta jest już zakończona")
        }

        reservation.status = ReservationStatus.COMPLETED
        reservationRepository.save(reservation)
    }

    /**
     * Marks a reservation as [ReservationStatus.NO_SHOW] and increments the customer's company-scoped no-show counter.
     * If the counter reaches the company's [pl.kacosmetology.scheduler.company.Company.maxNoShows] threshold
     * (and it is greater than zero), the customer is automatically blocked from online booking at that company.
     * Only PENDING or CONFIRMED reservations can be marked as no-show.
     */
    @Transactional
    fun markNoShow(reservationId: Long) {
        val reservation = reservationRepository.findById(reservationId)
            .orElseThrow { NoSuchElementException("Rezerwacja nie istnieje") }

        if (reservation.status != ReservationStatus.PENDING && reservation.status != ReservationStatus.CONFIRMED) {
            throw IllegalStateException("Tylko aktywna rezerwacja może być oznaczona jako nieobecność")
        }

        reservation.status = ReservationStatus.NO_SHOW
        reservationRepository.save(reservation)

        val block = companyCustomerBlockRepository
            .findByCompanyIdAndCustomerId(reservation.companyId, reservation.customerId)
            ?: CompanyCustomerBlock(companyId = reservation.companyId, customerId = reservation.customerId)
        block.noShowCount++

        val company = companyRepository.findById(reservation.companyId)
            .orElseThrow { NoSuchElementException("Firma nie istnieje") }
        if (company.maxNoShows > 0 && block.noShowCount >= company.maxNoShows) {
            block.blocked = true
        }

        companyCustomerBlockRepository.save(block)
    }

    /** Returns all reservations for a customer, ordered by start time descending. */
    @Transactional(readOnly = true)
    fun getCustomerReservations(customerId: Long): List<Reservation> {
        return reservationRepository.findAllByCustomerIdOrderByStartTimeDesc(customerId)
    }

    /** Returns an employee's schedule within a given time range. */
    @Transactional(readOnly = true)
    fun getEmployeeSchedule(employeeId: Long, start: LocalDateTime, end: LocalDateTime): List<Reservation> {
        return reservationRepository.findEmployeeSchedule(employeeId, start, end)
    }

    /**
     * Creates a reservation on behalf of a client, identified by phone number.
     * If no user with [customerPhone] exists, a new account is created using [customerFirstName] and [customerLastName].
     * Both name fields are required when the client does not exist yet.
     */
    @Transactional
    fun createReservationByStaff(
        employeeId: Long,
        serviceId: Long,
        startTime: LocalDateTime,
        customerPhone: String,
        customerFirstName: String?,
        customerLastName: String?
    ): Reservation {
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
            customerId = customer.id,
            employeeId = employeeId,
            offeringId = serviceId,
            startTime = startTime
        )
    }
}
