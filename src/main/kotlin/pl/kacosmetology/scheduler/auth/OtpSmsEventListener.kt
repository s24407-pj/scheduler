package pl.kacosmetology.scheduler.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import pl.kacosmetology.scheduler.auth.sms.SmsSender

/** Secret-bearing internal event; deliberately avoids a generated toString that could expose the code. */
class OtpIssuedEvent(val phoneNumber: String, val code: String)

/** Dispatches OTP SMS only after commit; delivery failure cannot invalidate an authentication transaction. */
@Component
class OtpSmsEventListener(private val smsSender: SmsSender) {
    private val log = LoggerFactory.getLogger(OtpSmsEventListener::class.java)

    /** Sends a committed issuance without allowing provider errors or secrets into the request or logs. */
    @TransactionalEventListener
    fun onOtpIssued(event: OtpIssuedEvent) {
        try {
            smsSender.sendOtp(event.phoneNumber, event.code)
        } catch (failure: Exception) {
            log.warn("OTP SMS dispatch failed: type={}", failure.javaClass.simpleName)
        }
    }
}
