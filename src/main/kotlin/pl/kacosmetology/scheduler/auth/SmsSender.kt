package pl.kacosmetology.scheduler.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Abstraction for sending OTP codes via SMS. */
interface SmsSender {
    fun sendOtp(phoneNumber: String, code: String)
}

/** Development implementation that logs OTP codes to the console instead of sending real SMS. */
@Service
class ConsoleSmsSender : SmsSender {
    private val logger = LoggerFactory.getLogger(ConsoleSmsSender::class.java)

    override fun sendOtp(phoneNumber: String, code: String) {
        logger.info("MOCK SMS to: $phoneNumber | OTP code: $code | Valid for 5 minutes")
    }
}