package pl.kacosmetology.scheduler.auth.sms

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class ConsoleSmsSenderTest {
    @Test
    fun `development adapter classifies acceptance without logging OTP secrets`() {
        val logger = LoggerFactory.getLogger(ConsoleSmsSender::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            val sender = ConsoleSmsSender()
            assertEquals(SmsDeliveryResult.ACCEPTED, sender.deliverOtp("+48500111222", "785419"))
            assertFalse(logs.list.any { it.formattedMessage.contains("785419") })
            assertEquals(SmsDeliveryResult.ACCEPTED, sender.deliverMessage("+48500111222", "Wizyta jutro"))
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }
}
