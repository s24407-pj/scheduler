package pl.kacosmetology.scheduler.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE_ID

class NotificationSchedulerTest {
    @Test
    fun `reminder trigger is previous calendar day nine Warsaw and completion is separate`() {
        val method = NotificationScheduler::class.java.getMethod("sendReminders")
        val schedule = method.getAnnotation(Scheduled::class.java)
        assertEquals("0 0 9 * * *", schedule.cron)
        assertEquals(BUSINESS_ZONE_ID, schedule.zone)
        assertFalse(NotificationScheduler::class.java.methods.any { it.name == "autoCompleteElapsedReservations" })
    }
}
