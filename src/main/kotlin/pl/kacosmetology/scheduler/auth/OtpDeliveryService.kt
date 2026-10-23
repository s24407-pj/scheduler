package pl.kacosmetology.scheduler.auth

import java.time.Clock
import java.time.Duration
import java.util.UUID
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
import pl.kacosmetology.scheduler.config.SmsWorkers

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
    workers: SmsWorkers,
) {
    private val log = LoggerFactory.getLogger(OtpDeliveryService::class.java)
    private val transactions =
        TransactionTemplate(manager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    @Volatile private var recovered = false
    private val worker =
        workers.create(
            "otp",
            { excluded, limit ->
                if (!recovered) emptyList()
                else
                    transactions
                        .execute {
                            val now = clock.instant()
                            repository.recoverAndExpire(now, false, excluded)
                            repository.due(now, excluded, limit)
                        }
                        .orEmpty()
            },
            ::deliver,
            repository::pendingCount,
        )

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
        check(worker.awaitIdle(Duration.ofSeconds(15))) { "Cannot recover an active OTP worker" }
        transactions.executeWithoutResult { repository.recoverAndExpire(clock.instant(), true) }
        recovered = true
    }

    /** Wakes the OTP coordinator; saturated notification work cannot occupy OTP execution slots. */
    fun dispatchDue() {
        if (recovered) worker.wake()
    }

    private fun prepare(id: UUID): OtpDelivery? {
        val row = repository.lock(id) ?: return null
        val now = clock.instant()
        if (row.state !in setOf("PENDING", "TEMPORARY_FAILURE") || row.nextAttemptAt.isAfter(now)) return null
        val code = row.code
        if (code == null || !now.isBefore(row.expiresAt) || otpStore.deliveryTtlMillis(row.phone, code) == null) {
            repository.result(id, "DISCARDED", now)
            return null
        }
        if (row.attempts > 0 || otpStore.firstDeliveryNeedsBudget(row.phone)) {
            val allowance = budget.reserve()
            if (!allowance.allowed) {
                val retry = now.plusSeconds(allowance.retryAfterSeconds)
                repository.result(
                    id,
                    if (retry.isBefore(row.expiresAt)) "TEMPORARY_FAILURE" else "DISCARDED",
                    now,
                    retry,
                )
                return null
            }
        }
        repository.sending(id, now)
        return row
    }

    private fun deliver(id: UUID) {
        val attempt = transactions.execute { prepare(id) } ?: return
        val code = requireNotNull(attempt.code)
        if (!clock.instant().isBefore(attempt.expiresAt) || otpStore.deliveryTtlMillis(attempt.phone, code) == null) {
            transactions.executeWithoutResult {
                if (repository.lock(id)?.state == "SENDING") repository.result(id, "DISCARDED", clock.instant())
            }
            return
        }
        val result =
            try {
                sender.deliverOtp(attempt.phone, code)
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
    }
}
