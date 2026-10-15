package pl.kacosmetology.scheduler.notification

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import pl.kacosmetology.scheduler.auth.sms.SmsDeliveryResult
import pl.kacosmetology.scheduler.auth.sms.SmsSender

class NotificationServiceTest {
    @Test
    fun `legacy successful provider explicitly bridges to accepted`() {
        val sender =
            object : SmsSender {
                override fun sendOtp(phoneNumber: String, code: String) = Unit

                override fun sendMessage(phoneNumber: String, message: String) = Unit
            }
        assertEquals(SmsDeliveryResult.ACCEPTED, sender.deliverMessage("+48111222333", "message"))
    }

    @Test
    fun `unclassified provider failure is ambiguous rather than retryable`() {
        val sender =
            object : SmsSender {
                override fun sendOtp(phoneNumber: String, code: String) = Unit

                override fun sendMessage(phoneNumber: String, message: String): Unit =
                    throw IllegalStateException("provider timeout")
            }
        assertEquals(SmsDeliveryResult.AMBIGUOUS, sender.deliverMessage("+48111222333", "message"))
    }
}
