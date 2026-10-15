package pl.kacosmetology.scheduler.notification

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import java.time.LocalDateTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationCancelledEvent
import pl.kacosmetology.scheduler.reservation.ReservationCreatedEvent
import pl.kacosmetology.scheduler.reservation.ReservationStatus

@ExtendWith(MockKExtension::class)
class NotificationEventListenerTest {

    @MockK private lateinit var notificationService: NotificationService

    @InjectMockKs private lateinit var listener: NotificationEventListener

    private val startTime = LocalDateTime.of(2026, 3, 15, 14, 0)

    private val reservation =
        Reservation(
            id = 100L,
            companyId = 5L,
            customerId = 1L,
            employeeId = 2L,
            serviceId = 10L,
            price = 200,
            startTime = startTime,
            endTime = startTime.plusHours(1),
            status = ReservationStatus.BOOKED,
        )

    @Test
    fun `onReservationCreated should swallow notification failure`() {
        every { notificationService.sendBookingConfirmation(reservation) } throws RuntimeException("SMS failed")

        assertDoesNotThrow { listener.onReservationCreated(ReservationCreatedEvent(reservation)) }

        verify { notificationService.sendBookingConfirmation(reservation) }
    }

    @Test
    fun `onReservationCancelled should swallow notification failure`() {
        every { notificationService.sendCancellationNotification(reservation) } throws RuntimeException("SMS failed")

        assertDoesNotThrow { listener.onReservationCancelled(ReservationCancelledEvent(reservation)) }

        verify { notificationService.sendCancellationNotification(reservation) }
    }
}
