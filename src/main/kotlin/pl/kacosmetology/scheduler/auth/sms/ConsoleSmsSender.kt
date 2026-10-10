package pl.kacosmetology.scheduler.auth.sms

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * Development implementation that logs OTP codes to the console instead of sending real SMS. Disabled in the `prod`
 * profile so OTP codes never reach production logs; prod requires a real [SmsSender].
 */
@Service
@Profile("!prod")
class ConsoleSmsSender : SmsSender {
    private val logger = LoggerFactory.getLogger(ConsoleSmsSender::class.java)

    /** Logs the OTP to the console instead of sending a real SMS. */
    override fun sendOtp(phoneNumber: String, code: String) {
        logger.info("MOCK SMS to: $phoneNumber | OTP code: $code")
    }

    /** Logs the message to the console instead of sending a real SMS. */
    override fun sendMessage(phoneNumber: String, message: String) {
        logger.info("MOCK SMS to $phoneNumber | $message")
    }
}
