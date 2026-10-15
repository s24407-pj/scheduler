package pl.kacosmetology.scheduler.notification

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import pl.kacosmetology.scheduler.reservation.ReservationChangedEvent

class NotificationEventListenerTest {
    @Test
    fun `after commit dispatch failure never escapes to staff operation`() {
        val outbox = mockk<NotificationOutbox>()
        val dispatcher = mockk<NotificationService>()
        every { dispatcher.dispatchDue() } throws IllegalStateException("DB temporarily unavailable")
        val listener = NotificationEventListener(outbox, dispatcher)
        assertDoesNotThrow { listener.afterReservationChanged(ReservationChangedEvent(1, "EDIT", false)) }
        verify(exactly = 1) { dispatcher.dispatchDue() }
    }
}
