package pl.kacosmetology.scheduler.auth

import java.util.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.kacosmetology.scheduler.config.EntityIds

/** Secret-bearing internal event; deliberately avoids a generated toString that could expose the code. */
class OtpIssuedEvent(val phoneNumber: String, val code: String, val id: UUID = EntityIds.next())

/** Successful consumption invalidates pending delivery without exposing the consumed secret in diagnostic strings. */
class OtpConsumedEvent(val phoneNumber: String, val code: String)

/** Persists intent before commit, then wakes a durable worker after commit; provider failure cannot undo login work. */
@Component
class OtpSmsEventListener(private val deliveries: OtpDeliveryService) {
    private val log = LoggerFactory.getLogger(OtpSmsEventListener::class.java)

    /** Atomically records phone-only intent with the OTP request transaction, without creating an account/profile. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun persist(event: OtpIssuedEvent) = deliveries.stage(event)

    /** Scrubs an unneeded pending secret in the successful verification transaction. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun consumed(event: OtpConsumedEvent) = deliveries.discardConsumed(event)

    /** Nonblocking wake for committed intent; the request never waits for a provider or worker slot. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onOtpIssued(event: OtpIssuedEvent) {
        try {
            deliveries.dispatchDue()
        } catch (failure: Exception) {
            log.warn("OTP delivery deferred: type={}", failure.javaClass.simpleName)
        }
    }
}
