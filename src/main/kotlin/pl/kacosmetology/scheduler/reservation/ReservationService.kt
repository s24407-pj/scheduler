package pl.kacosmetology.scheduler.reservation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.treatment.TreatmentRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import java.time.LocalDateTime

/** Core business logic for reservation lifecycle: create, cancel, complete. */
@Service
class ReservationService(
    private val reservationRepository: ReservationRepository,
    private val serviceRepository: TreatmentRepository,
    private val userRepository: UserRepository
) {

    /**
     * Creates a new reservation with a price snapshot from the service catalog.
     * Validates that the requested time slot is available.
     */
    @Transactional
    fun createReservation(
        customerId: Long,
        employeeId: Long,
        serviceId: Long,
        startTime: LocalDateTime
    ): Reservation {
        val service = serviceRepository.findById(serviceId)
            .orElseThrow { IllegalArgumentException("Usługa nie istnieje") }

        if (!service.active) {
            throw IllegalArgumentException("Ta usługa nie jest już dostępna")
        }

        val endTime = startTime.plusMinutes(service.durationMinutes.toLong())

        if (reservationRepository.existsOverlapping(employeeId, startTime, endTime)) {
            throw IllegalStateException("Ten termin jest już zajęty")
        }

        return reservationRepository.save(
            Reservation(
                companyId = service.companyId,
                customerId = customerId,
                employeeId = employeeId,
                serviceId = serviceId,
                price = service.price,
                startTime = startTime,
                endTime = endTime,
                status = ReservationStatus.PENDING
            )
        )
    }

    /** Cancels a reservation. Only the owning customer can cancel, and only if not already completed. */
    @Transactional
    fun cancelReservation(reservationId: Long, customerId: Long) {
        val reservation = reservationRepository.findById(reservationId)
            .orElseThrow { IllegalArgumentException("Rezerwacja nie istnieje") }

        if (reservation.customerId != customerId) {
            throw IllegalStateException("Nie możesz anulować nie swojej rezerwacji")
        }

        if (reservation.status == ReservationStatus.COMPLETED) {
            throw IllegalStateException("Nie można anulować wizyty, która już się odbyła")
        }

        reservation.status = ReservationStatus.CANCELLED
        reservationRepository.save(reservation)
    }

    /** Marks a reservation as completed. Called by staff members. */
    @Transactional
    fun completeReservation(reservationId: Long) {
        val reservation = reservationRepository.findById(reservationId)
            .orElseThrow { IllegalArgumentException("Rezerwacja nie istnieje") }

        if (reservation.status == ReservationStatus.CANCELLED) {
            throw IllegalStateException("Nie można zakończyć odwołanej wizyty")
        }

        reservation.status = ReservationStatus.COMPLETED
        reservationRepository.save(reservation)
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
            serviceId = serviceId,
            startTime = startTime
        )
    }
}