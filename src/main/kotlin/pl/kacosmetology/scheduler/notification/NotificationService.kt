package pl.kacosmetology.scheduler.notification

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.user.UserRepository
import java.time.format.DateTimeFormatter

/** Sends SMS notifications to customers about their reservations. */
@Service
class NotificationService(
    private val smsSender: SmsSender,
    private val userRepository: UserRepository,
    private val offeringRepository: OfferingRepository,
    private val companyRepository: CompanyRepository
) {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Sends a booking confirmation SMS to the customer.
     * Failures are logged but not propagated — notifications are a side-effect.
     */
    fun sendBookingConfirmation(reservation: Reservation) {
        runCatching {
            val (phone, serviceName, employeeName, companyName) = loadDetails(reservation)
            val date = reservation.startTime.format(dateFormatter)
            smsSender.sendMessage(
                phone,
                "Wizyta zarezerwowana! $serviceName u $employeeName — $date. Salon $companyName. Do zobaczenia!"
            )
        }.onFailure { logger.warn("Failed to send booking confirmation for reservation ${reservation.id}", it) }
    }

    /**
     * Sends a cancellation notification SMS to the customer.
     * Failures are logged but not propagated — notifications are a side-effect.
     */
    fun sendCancellationNotification(reservation: Reservation) {
        runCatching {
            val (phone, serviceName, _, companyName) = loadDetails(reservation)
            val date = reservation.startTime.format(dateFormatter)
            smsSender.sendMessage(phone, "Rezerwacja odwołana: $serviceName $date. Salon $companyName.")
        }.onFailure { logger.warn("Failed to send cancellation notification for reservation ${reservation.id}", it) }
    }

    /**
     * Sends a reminder SMS to the customer ~24h before the appointment.
     * Failures are logged but not propagated — notifications are a side-effect.
     */
    fun sendReminder(reservation: Reservation) {
        runCatching {
            val (phone, serviceName, employeeName, companyName) = loadDetails(reservation)
            val time = reservation.startTime.format(timeFormatter)
            smsSender.sendMessage(
                phone,
                "Przypomnienie: jutro o $time — $serviceName u $employeeName. Salon $companyName. Do zobaczenia!"
            )
        }.onFailure { logger.warn("Failed to send reminder for reservation ${reservation.id}", it) }
    }

    private data class ReservationDetails(
        val customerPhone: String,
        val serviceName: String,
        val employeeName: String,
        val companyName: String
    )

    private fun loadDetails(reservation: Reservation): ReservationDetails {
        val customer = userRepository.findById(reservation.customerId)
            .orElseThrow { NoSuchElementException("Customer ${reservation.customerId} not found") }
        val offering = offeringRepository.findById(reservation.serviceId)
            .orElseThrow { NoSuchElementException("Service ${reservation.serviceId} not found") }
        val employee = userRepository.findById(reservation.employeeId)
            .orElseThrow { NoSuchElementException("Employee ${reservation.employeeId} not found") }
        val company = companyRepository.findById(reservation.companyId)
            .orElseThrow { NoSuchElementException("Company ${reservation.companyId} not found") }

        return ReservationDetails(
            customerPhone = customer.phoneNumber,
            serviceName = offering.name,
            employeeName = "${employee.firstName} ${employee.lastName}",
            companyName = company.name
        )
    }
}
