package pl.kacosmetology.scheduler.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.kacosmetology.scheduler.auth.sms.SmsDeliveryResult
import pl.kacosmetology.scheduler.auth.sms.SmsSender

class OtpDeliveryServiceTest {
    @Test
    fun `successful legacy OTP provider maps to acceptance`() {
        val provider =
            object : SmsSender {
                override fun sendOtp(phoneNumber: String, code: String) = Unit

                override fun sendMessage(phoneNumber: String, message: String) = Unit
            }
        assertEquals(SmsDeliveryResult.ACCEPTED, provider.deliverOtp("+48880000001", "123456"))
    }

    @Test
    fun `legacy provider exception maps to ambiguous without exposing its secret-bearing message`() {
        val provider =
            object : SmsSender {
                override fun sendOtp(phoneNumber: String, code: String): Unit = error("unclassified provider failure")

                override fun sendMessage(phoneNumber: String, message: String) = Unit
            }
        assertEquals(SmsDeliveryResult.AMBIGUOUS, provider.deliverOtp("+48880000001", "123456"))
    }
}
