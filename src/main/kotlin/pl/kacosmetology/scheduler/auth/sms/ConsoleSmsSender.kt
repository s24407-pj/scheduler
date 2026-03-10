package pl.kacosmetology.scheduler.auth.sms

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Development implementation that logs OTP codes to the console instead of sending real SMS. */
@Service
class ConsoleSmsSender : SmsSender {
    private val logger = LoggerFactory.getLogger(ConsoleSmsSender::class.java)

    /** Logs the OTP to the console instead of sending a real SMS. */
    override fun sendOtp(phoneNumber: String, code: String) {
        logger.info("MOCK SMS to: $phoneNumber | OTP code: $code | Valid for 5 minutes")
    }

    /** Logs the message to the console instead of sending a real SMS. */
    override fun sendMessage(phoneNumber: String, message: String) {
        logger.info("MOCK SMS to $phoneNumber | $message")
    }
}
