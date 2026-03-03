package pl.kacosmetology.scheduler.notification

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.reservation.ReservationStatus
import java.time.LocalDateTime

@ExtendWith(MockKExtension::class)
class NotificationSchedulerTest {

    @MockK
    private lateinit var reservationRepository: ReservationRepository

    @MockK
    private lateinit var notificationService: NotificationService

    @InjectMockKs
    private lateinit var notificationScheduler: NotificationScheduler

    private val startTime = LocalDateTime.now().plusHours(24)

    private val reservation = Reservation(
        id = 1L,
        companyId = 5L,
        customerId = 1L,
        employeeId = 2L,
        serviceId = 10L,
        price = 200,
        startTime = startTime,
        endTime = startTime.plusHours(1),
        status = ReservationStatus.CONFIRMED
    )

    @Test
    fun `sendReminders should call sendReminder and bulk-mark sent when reservation found`() {
        every { reservationRepository.findPendingReminders(any(), any()) } returns listOf(reservation)
        every { notificationService.sendReminder(reservation) } just runs
        every { reservationRepository.markRemindersAsSent(listOf(1L)) } just runs

        notificationScheduler.sendReminders()

        verify { notificationService.sendReminder(reservation) }
        verify { reservationRepository.markRemindersAsSent(listOf(1L)) }
    }

    @Test
    fun `sendReminders should not call sendReminder when no pending reservations`() {
        every { reservationRepository.findPendingReminders(any(), any()) } returns emptyList()

        notificationScheduler.sendReminders()

        verify(exactly = 0) { notificationService.sendReminder(any()) }
        verify(exactly = 0) { reservationRepository.markRemindersAsSent(any()) }
    }

    @Test
    fun `sendReminders should not mark reservation when sendReminder throws`() {
        every { reservationRepository.findPendingReminders(any(), any()) } returns listOf(reservation)
        every { notificationService.sendReminder(reservation) } throws RuntimeException("SMS failed")

        notificationScheduler.sendReminders()

        verify(exactly = 0) { reservationRepository.markRemindersAsSent(any()) }
    }
}
