package pl.kacosmetology.scheduler.auth

import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import pl.kacosmetology.scheduler.auth.sms.DailySmsBudget
import pl.kacosmetology.scheduler.auth.sms.SmsDeliveryResult
import pl.kacosmetology.scheduler.auth.sms.SmsSender

/**
 * Single-instance durable OTP worker. Issuance reserves the first attempt; subsequent attempts share the daily budget.
 */
@Service
class OtpDeliveryService(
    private val repository: OtpDeliveryRepository,
    private val otpStore: OtpStore,
    private val sender: SmsSender,
    private val budget: DailySmsBudget,
    manager: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OtpDeliveryService::class.java)
    private val transactions =
        TransactionTemplate(manager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    @Volatile private var recovered = false

    /** Persists the remaining original Redis lifetime; neither database intent nor a retry extends code validity. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun stage(event: OtpIssuedEvent) {
        repository.lockPhone(event.phoneNumber)
        val now = clock.instant()
        val remaining = otpStore.deliveryTtlMillis(event.phoneNumber, event.code)
        if (remaining != null) repository.discardOtherPending(event.phoneNumber, event.code, now)
        repository.insert(
            event.id,
            event.phoneNumber,
            if (remaining == null) null else event.code,
            now,
            now.plusMillis(remaining ?: 0),
        )
    }

    /** Removes a successfully consumed pending secret, preserving any newer replacement delivery. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun discardConsumed(event: OtpConsumedEvent) {
        repository.discardConsumed(event.phoneNumber, event.code, clock.instant())
    }

    /** Startup never blindly repeats an unknown in-flight provider call; pending live work remains retryable. */
    @Synchronized
    fun recoverAfterRestart() {
        recovered = false
        transactions.executeWithoutResult { repository.recoverAndExpire(clock.instant(), true) }
        recovered = true
    }

    /** Sends only committed, unexpired intent whose exact code still matches the atomic Redis OTP state. */
    @Synchronized
    fun dispatchDue() {
        if (!recovered) return
        val now = clock.instant()
        val ids =
            transactions
                .execute {
                    repository.recoverAndExpire(now, false)
                    repository.due(now)
                }
                .orEmpty()
        ids.forEach { id ->
            try {
                val attempt =
                    transactions.execute {
                        val row = repository.lock(id) ?: return@execute null
                        if (row.state !in setOf("PENDING", "TEMPORARY_FAILURE")) return@execute null
                        val code = row.code
                        if (
                            code == null ||
                                !clock.instant().isBefore(row.expiresAt) ||
                                otpStore.deliveryTtlMillis(row.phone, code) == null
                        ) {
                            repository.result(id, "DISCARDED", clock.instant())
                            return@execute null
                        }
                        val current = clock.instant()
                        if (row.attempts > 0 || otpStore.firstDeliveryNeedsBudget(row.phone)) {
                            val allowance = budget.reserve()
                            if (!allowance.allowed) {
                                val retry = current.plusSeconds(allowance.retryAfterSeconds)
                                repository.result(
                                    id,
                                    if (retry.isBefore(row.expiresAt)) "TEMPORARY_FAILURE" else "DISCARDED",
                                    current,
                                    retry,
                                )
                                return@execute null
                            }
                        }
                        repository.sending(id, current)
                        row
                    } ?: return@forEach
                val result =
                    try {
                        sender.deliverOtp(attempt.phone, requireNotNull(attempt.code))
                    } catch (_: Exception) {
                        SmsDeliveryResult.AMBIGUOUS
                    }
                transactions.executeWithoutResult {
                    val row = repository.lock(id) ?: return@executeWithoutResult
                    if (row.state != "SENDING") return@executeWithoutResult
                    val current = clock.instant()
                    val retry = current.plusSeconds(30)
                    val state =
                        when (result) {
                            SmsDeliveryResult.ACCEPTED -> "ACCEPTED"
                            SmsDeliveryResult.AMBIGUOUS -> "AMBIGUOUS"
                            SmsDeliveryResult.PERMANENT_REJECTION -> "DISCARDED"
                            SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE ->
                                if (
                                    retry.isBefore(row.expiresAt) &&
                                        row.code != null &&
                                        otpStore.deliveryTtlMillis(row.phone, row.code) != null
                                )
                                    "TEMPORARY_FAILURE"
                                else "DISCARDED"
                        }
                    repository.result(id, state, current, retry)
                    if (result != SmsDeliveryResult.ACCEPTED) log.warn("OTP delivery {} result={}", id, result)
                }
            } catch (failure: Exception) {
                log.warn("OTP delivery {} deferred: type={}", id, failure.javaClass.simpleName)
            }
        }
    }
}
