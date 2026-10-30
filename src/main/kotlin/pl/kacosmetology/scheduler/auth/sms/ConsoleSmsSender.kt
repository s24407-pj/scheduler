package pl.kacosmetology.scheduler.auth.sms

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * Development implementation that records OTP dispatch to the console instead of sending real SMS. Disabled in the
 * `prod` profile so OTP codes never reach production logs; prod requires a real [SmsSender].
 */
@Service
@Profile("!prod")
class ConsoleSmsSender : SmsSender {
    private val logger = LoggerFactory.getLogger(ConsoleSmsSender::class.java)

    /** Records a development dispatch without logging the secret OTP. */
    override fun deliverOtp(phoneNumber: String, code: String): SmsDeliveryResult {
        logger.info("MOCK OTP SMS dispatch to {}", phoneNumber)
        return SmsDeliveryResult.ACCEPTED
    }

    /** Logs the message to the console instead of sending a real SMS. */
    override fun deliverMessage(phoneNumber: String, message: String): SmsDeliveryResult {
        logger.info("MOCK SMS to $phoneNumber | $message")
        return SmsDeliveryResult.ACCEPTED
    }
}
