package pl.kacosmetology.scheduler.notification

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import pl.kacosmetology.scheduler.auth.sms.DailySmsBudget
import pl.kacosmetology.scheduler.auth.sms.SmsDeliveryResult
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import pl.kacosmetology.scheduler.config.SmsWorkers

/** Single-instance durable SMS dispatcher with conservative ambiguous-send recovery and bounded retries. */
@Service
class NotificationService(
    private val repository: NotificationDeliveryRepository,
    private val details: NotificationDetails,
    private val smsSender: SmsSender,
    private val budget: DailySmsBudget,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
    workers: SmsWorkers,
) {
    private val log = LoggerFactory.getLogger(NotificationService::class.java)
    private val transactions =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
    @Volatile private var activeRun: ReminderRun? = null
    @Volatile private var recovered = false
    private val reminderRequested = AtomicReference<ReminderRun?>()
    private val worker =
        workers.create(
            "notification",
            { excluded, limit ->
                if (!recovered) emptyList()
                else {
                    reminderRequested.get()?.let { requested ->
                        beginReminderRun(requested)
                        reminderRequested.compareAndSet(requested, null)
                    }
                    transactions.execute { repository.due(clock.instant(), excluded, limit) }.orEmpty()
                }
            },
            ::deliver,
            repository::pendingCount,
            repository::sendingStats,
        )

    /**
     * Recovers only delivery state; missed 09:00 reminder runs are deliberately never started at application startup.
     */
    fun recoverAfterRestart() {
        recovered = false
        check(worker.awaitIdle(Duration.ofSeconds(15))) { "Cannot recover an active notification worker" }
        reminderRequested.set(null)
        activeRun = null
        transactions.executeWithoutResult { repository.recover(clock.instant()) }
        recovered = true
    }

    /** Wakes the isolated coordinator without doing database or provider work on the caller's thread. */
    fun dispatchDue() {
        if (recovered) worker.wake()
    }

    private fun deliver(id: UUID) {
        val attempt = transactions.execute { prepare(id) } ?: return
        // A task can cross its deadline while committing SENDING; do not begin a late provider request.
        val now = clock.instant()
        if (
            !now.isBefore(attempt.deadline) ||
                (attempt.delivery.kind == "REMINDER" && activeRun?.token != attempt.delivery.runToken)
        ) {
            transactions.executeWithoutResult {
                val current = repository.lock(id)
                if (current?.state == DeliveryState.SENDING)
                    repository.state(id, DeliveryState.DISCARDED, clock.instant())
            }
            return
        }
        val result =
            try {
                smsSender.deliverMessage(attempt.phone, attempt.message)
            } catch (_: Exception) {
                SmsDeliveryResult.AMBIGUOUS
            }
        transactions.executeWithoutResult { finish(attempt, result) }
    }

    /** Retains today's requested run until initialization commits or the window closes; startup never restores it. */
    fun startReminderRun() {
        if (!recovered) return
        val now = clock.instant()
        val date = now.atZone(BUSINESS_ZONE).toLocalDate()
        val cutoff = date.atTime(9, 0).atZone(BUSINESS_ZONE).toInstant()
        val deadline = date.atTime(10, 0).atZone(BUSINESS_ZONE).toInstant()
        if (now.isBefore(cutoff) || !now.isBefore(deadline)) return
        reminderRequested.updateAndGet { previous ->
            if (previous?.date == date) previous else ReminderRun(UUID.randomUUID(), date, cutoff, deadline)
        }
        worker.wake()
    }

    private fun beginReminderRun(run: ReminderRun) {
        val now = clock.instant()
        if (!recovered || now.isBefore(run.cutoff) || !now.isBefore(run.deadline) || activeRun?.date == run.date) return
        transactions.executeWithoutResult {
            details.reminderCandidates(run.date.plusDays(1)).forEach { id ->
                val reservation = details.reservation(id) ?: return@forEach
                if (details.eligibleAtCutoff(id, run.cutoff))
                    repository.insert("REMINDER", reservation, now, run.deadline, run.token)
            }
        }
        // Publish ownership only after commit. Retain the requested token if a commit acknowledgement was lost.
        activeRun = run
    }

    private fun prepare(id: UUID): Attempt? {
        val now = clock.instant()
        val delivery = repository.lock(id) ?: return null
        if (
            delivery.state !in setOf(DeliveryState.PENDING, DeliveryState.TEMPORARY_FAILURE) ||
                delivery.nextAttemptAt.isAfter(now)
        )
            return null
        val members =
            if (delivery.kind == "BLOCK_SUMMARY")
                repository.summaryMembers(id).mapNotNull(details::reservation).filter {
                    it.companyId == delivery.companyId &&
                        it.customerId == delivery.customerId &&
                        it.status == "CANCELLED" &&
                        it.start.isAfter(now)
                }
            else emptyList()
        val reservation = delivery.reservationId?.let(details::reservation)
        val deadline: Instant
        val phone: String
        val message: String
        if (delivery.kind == "BLOCK_SUMMARY") {
            if (members.isEmpty()) return discard(delivery, now)
            deadline = members.maxOf { it.start }
            phone = members.first().phone
            message = details.summary(members)
        } else {
            if (
                reservation == null ||
                    reservation.archived ||
                    reservation.companyId != delivery.companyId ||
                    reservation.customerId != delivery.customerId
            )
                return discard(delivery, now)
            if (delivery.kind == "REMINDER") {
                val run = activeRun
                if (
                    run == null ||
                        delivery.runToken != run.token ||
                        !now.isBefore(run.deadline) ||
                        reservation.status != "BOOKED" ||
                        reservation.date != delivery.date ||
                        !details.eligibleAtCutoff(reservation.id, run.cutoff)
                )
                    return discard(delivery, now)
                deadline = run.deadline
            } else {
                if (
                    reservation.revision != delivery.revision ||
                        !reservation.end.isAfter(now) ||
                        (delivery.kind == "CANCEL" && reservation.status != "CANCELLED") ||
                        (delivery.kind != "CANCEL" && reservation.status != "BOOKED")
                )
                    return discard(delivery, now)
                if (delivery.kind != "CANCEL") {
                    repository.reminderEffect(reservation.id, reservation.date, reservation.revision)?.let {
                        repository.state(delivery.id, it, now)
                        return null
                    }
                }
                if (!reservation.start.isAfter(now) && delivery.attempts > 0) return discard(delivery, now)
                deadline = if (reservation.start.isAfter(now)) reservation.start else reservation.end
            }
            phone = reservation.phone
            message = details.message(delivery.kind, reservation)
        }
        if (!now.isBefore(deadline)) return discard(delivery, now)
        val allowance = budget.reserve()
        if (!allowance.allowed) {
            val retry = now.plusSeconds(allowance.retryAfterSeconds)
            repository.state(
                id,
                if (retry.isBefore(deadline)) DeliveryState.TEMPORARY_FAILURE else DeliveryState.DISCARDED,
                now,
                retry,
                deadline,
            )
            return null
        }
        repository.sending(id, now, deadline)
        return Attempt(delivery, phone, message, deadline, reservation?.revision ?: delivery.revision)
    }

    private fun finish(attempt: Attempt, result: SmsDeliveryResult) {
        val now = clock.instant()
        val current = repository.lock(attempt.delivery.id) ?: return
        if (current.state != DeliveryState.SENDING) return
        val state =
            when (result) {
                SmsDeliveryResult.ACCEPTED -> DeliveryState.ACCEPTED
                SmsDeliveryResult.AMBIGUOUS -> DeliveryState.AMBIGUOUS
                SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE -> {
                    val ongoing =
                        current.kind != "REMINDER" &&
                            current.reservationId?.let(details::reservation)?.start?.isAfter(now) == false
                    if (
                        ongoing ||
                            !now.plusSeconds(30).isBefore(attempt.deadline) ||
                            (current.kind == "REMINDER" && activeRun?.token != current.runToken)
                    )
                        DeliveryState.DISCARDED
                    else DeliveryState.TEMPORARY_FAILURE
                }
            }
        repository.state(current.id, state, now, now.plusSeconds(30), attempt.deadline)
        if (result != SmsDeliveryResult.ACCEPTED) log.warn("SMS delivery {} result={}", current.id, result)
        if (current.kind == "REMINDER" && state in setOf(DeliveryState.ACCEPTED, DeliveryState.AMBIGUOUS)) {
            repository.suppressConfirmations(
                requireNotNull(current.reservationId),
                requireNotNull(current.date),
                requireNotNull(attempt.revision),
                state == DeliveryState.AMBIGUOUS,
                now,
            )
        }
    }

    private fun discard(delivery: Delivery, now: Instant): Attempt? {
        repository.state(delivery.id, DeliveryState.DISCARDED, now)
        return null
    }

    private data class ReminderRun(val token: UUID, val date: LocalDate, val cutoff: Instant, val deadline: Instant)

    private data class Attempt(
        val delivery: Delivery,
        val phone: String,
        val message: String,
        val deadline: Instant,
        val revision: Long?,
    )
}
