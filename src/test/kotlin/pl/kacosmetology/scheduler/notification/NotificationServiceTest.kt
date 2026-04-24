package pl.kacosmetology.scheduler.notification

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationStatus
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import java.time.LocalDateTime
import java.util.*

@ExtendWith(MockKExtension::class)
class NotificationServiceTest {

    @MockK
    private lateinit var smsSender: SmsSender

    @MockK
    private lateinit var userRepository: UserRepository

    @MockK
    private lateinit var offeringRepository: OfferingRepository

    @MockK
    private lateinit var companyRepository: CompanyRepository

    @InjectMockKs
    private lateinit var notificationService: NotificationService

    private val customer = User(id = 1L, phoneNumber = "+48123456789", firstName = "Anna", lastName = "Kowalska")
    private val employee = User(id = 2L, phoneNumber = "+48987654321", firstName = "Jan", lastName = "Nowak")
    private val service = Offering(id = 10L, companyId = 5L, name = "Masaż", durationMinutes = 60, price = 200)
    private val company = Company(id = 5L, name = "Salon Piękności")
    private val startTime = LocalDateTime.of(2026, 3, 15, 14, 0)

    private val reservation = Reservation(
        id = 100L,
        companyId = 5L,
        customerId = 1L,
        employeeId = 2L,
        serviceId = 10L,
        price = 200,
        startTime = startTime,
        endTime = startTime.plusHours(1),
        status = ReservationStatus.PENDING
    )

    private fun setupMocks() {
        every { userRepository.findById(1L) } returns Optional.of(customer)
        every { userRepository.findById(2L) } returns Optional.of(employee)
        every { offeringRepository.findById(10L) } returns Optional.of(service)
        every { companyRepository.findById(5L) } returns Optional.of(company)
        every { smsSender.sendMessage(any(), any()) } returns Unit
    }

    @Test
    fun `sendBookingConfirmation should send SMS to customer phone with service name in message`() {
        setupMocks()
        val messageSlot = slot<String>()

        every { smsSender.sendMessage(any(), capture(messageSlot)) } returns Unit

        notificationService.sendBookingConfirmation(reservation)

        verify { smsSender.sendMessage(customer.phoneNumber, any()) }
        assertTrue(messageSlot.captured.contains("Masaż"), "Message should contain service name")
        assertTrue(messageSlot.captured.contains("Jan Nowak"), "Message should contain employee name")
        assertTrue(messageSlot.captured.contains("Salon Piękności"), "Message should contain company name")
    }

    @Test
    fun `sendCancellationNotification should send SMS to customer phone with service name in message`() {
        setupMocks()
        val messageSlot = slot<String>()

        every { smsSender.sendMessage(any(), capture(messageSlot)) } returns Unit

        notificationService.sendCancellationNotification(reservation)

        verify { smsSender.sendMessage(customer.phoneNumber, any()) }
        assertTrue(messageSlot.captured.contains("Masaż"), "Message should contain service name")
        assertTrue(messageSlot.captured.contains("Salon Piękności"), "Message should contain company name")
    }

    @Test
    fun `sendReminder should send SMS to customer phone with service name and time in message`() {
        setupMocks()
        val messageSlot = slot<String>()

        every { smsSender.sendMessage(any(), capture(messageSlot)) } returns Unit

        notificationService.sendReminder(reservation)

        verify { smsSender.sendMessage(customer.phoneNumber, any()) }
        assertTrue(messageSlot.captured.contains("Masaż"), "Message should contain service name")
        assertTrue(messageSlot.captured.contains("14:00"), "Message should contain appointment time")
        assertTrue(messageSlot.captured.contains("Jan Nowak"), "Message should contain employee name")
    }

    @Test
    fun `sendBookingConfirmation should not throw when customer not found`() {
        every { userRepository.findById(1L) } returns Optional.empty()

        notificationService.sendBookingConfirmation(reservation)

        verify(exactly = 0) { smsSender.sendMessage(any(), any()) }
    }
}
