package pl.kacosmetology.scheduler.auth.sms

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/** Secret-free reference to durable work; lane is an internal serialization key, never a metric label or log field. */
data class SmsWork(val id: UUID, val lane: String, val createdAt: Instant)

/** Snapshot of unresolved provider attempts, retaining no recipient or secret data. */
data class SmsSendingStats(val count: Long, val oldest: Instant?)

/**
 * Bounded execution over a durable database queue. Only the coordinator selects work; at most [concurrency] references
 * are admitted, and no payload is copied into an unbounded executor queue. Provider calls run on separate threads.
 */
class SmsWorker(
    private val kind: String,
    private val concurrency: Int,
    registry: MeterRegistry,
    private val clock: Clock,
    private val source: (Set<String>, Int) -> List<SmsWork>,
    private val process: (UUID) -> Unit,
    private val pending: () -> Long,
    private val sending: () -> SmsSendingStats = { SmsSendingStats(0, null) },
) {
    private val log = LoggerFactory.getLogger(SmsWorker::class.java)
    private val closing = AtomicBoolean()
    private val scheduled = AtomicBoolean()
    private val coordinating = AtomicInteger()
    private val slots = AtomicInteger()
    private val backlog = AtomicLong()
    private val sendingCount = AtomicLong()
    private val oldestSending = AtomicReference<Instant?>()
    private val active = mutableSetOf<String>()
    private val coordinator =
        Executors.newSingleThreadExecutor(Thread.ofPlatform().name("sms-$kind-coordinator").factory())
    private val executor =
        ThreadPoolExecutor(
            concurrency,
            concurrency,
            0,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(concurrency),
            Thread.ofPlatform().name("sms-$kind-", 0).factory(),
            ThreadPoolExecutor.AbortPolicy(),
        )
    private val waitTimer = Timer.builder("scheduler.sms.worker.wait").tag("kind", kind).register(registry)

    init {
        require(concurrency in 1..16) { "SMS concurrency must be between 1 and 16" }
        Gauge.builder("scheduler.sms.worker.admitted", slots) { it.get().toDouble() }
            .tag("kind", kind)
            .register(registry)
        Gauge.builder("scheduler.sms.worker.pending", backlog) { it.get().toDouble() }
            .tag("kind", kind)
            .register(registry)
        Gauge.builder("scheduler.sms.worker.sending", sendingCount) { it.get().toDouble() }
            .tag("kind", kind)
            .register(registry)
        Gauge.builder("scheduler.sms.worker.sending.oldest.age", oldestSending) {
                it.get()?.let { start -> Duration.between(start, clock.instant()).seconds.coerceAtLeast(0).toDouble() }
                    ?: 0.0
            }
            .baseUnit("seconds")
            .tag("kind", kind)
            .register(registry)
    }

    /** Coalesces wakeups; never queries the database, waits for capacity or calls a provider in the caller's thread. */
    fun wake() {
        if (closing.get() || !scheduled.compareAndSet(false, true)) return
        if (
            !coordinate {
                scheduled.set(false)
                fill()
            }
        )
            scheduled.set(false)
    }

    private fun coordinate(allowClosing: Boolean = false, action: () -> Unit): Boolean {
        if (closing.get() && !allowClosing) return false
        coordinating.incrementAndGet()
        try {
            coordinator.execute {
                try {
                    if (!closing.get() || allowClosing) action()
                } catch (failure: Exception) {
                    log.warn("SMS {} coordinator deferred work: type={}", kind, failure.javaClass.simpleName)
                } finally {
                    coordinating.decrementAndGet()
                }
            }
        } catch (_: RejectedExecutionException) {
            coordinating.decrementAndGet()
            return false
        }
        return true
    }

    private fun fill() {
        if (closing.get()) return
        val capacity = concurrency - active.size
        // Also run maintenance when full (for example OTP expiry); source never admits more than capacity.
        val work = source(active.toSet(), capacity)
        observe()
        for (item in work.take(capacity)) {
            if (closing.get()) break
            if (!active.add(item.lane)) continue
            slots.incrementAndGet()
            try {
                executor.execute {
                    var success = false
                    try {
                        waitTimer.record(Duration.between(item.createdAt, clock.instant()).coerceAtLeast(Duration.ZERO))
                        MDC.putCloseable("smsDeliveryId", item.id.toString()).use { process(item.id) }
                        success = true
                    } catch (failure: Exception) {
                        log.warn("SMS {} work {} deferred: type={}", kind, item.id, failure.javaClass.simpleName)
                    } finally {
                        coordinate(allowClosing = true) {
                            active.remove(item.lane)
                            slots.decrementAndGet()
                            // Failed preparations wait for another wakeup instead of spinning on the same row.
                            if (success) fill() else observe()
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                active.remove(item.lane)
                slots.decrementAndGet()
            }
        }
    }

    private fun observe() {
        try {
            // Read the complete snapshot before publishing; diagnostics must never gate selected delivery work.
            val pendingCount = pending()
            val stats = sending()
            val age = stats.oldest?.let { Duration.between(it, clock.instant()).seconds } ?: 0
            backlog.set(pendingCount)
            sendingCount.set(stats.count)
            oldestSending.set(stats.oldest)
            if (age >= 120)
                log.warn("SMS {} unresolved SENDING work: count={} oldestAgeSeconds={}", kind, stats.count, age)
        } catch (failure: Exception) {
            log.warn("SMS {} diagnostics unavailable: type={}", kind, failure.javaClass.simpleName)
        }
    }

    /**
     * Waits for the current drain to settle, including its coordinator; intended for controlled shutdown and checks.
     */
    fun awaitIdle(timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (slots.get() != 0 || coordinating.get() != 0 || scheduled.get()) {
            if (System.nanoTime() >= deadline) return false
            TimeUnit.MILLISECONDS.sleep(5)
        }
        return true
    }

    /** Stops admission immediately; already admitted work may complete, while the durable backlog stays untouched. */
    fun stopAdmission() {
        closing.set(true)
        scheduled.set(false)
        executor.shutdown()
    }

    /** Uses a shared absolute shutdown deadline so multiple pools cannot multiply the shutdown wait. */
    fun awaitTermination(deadlineNanos: Long) {
        for (pool in listOf(executor, coordinator)) {
            if (pool === coordinator) coordinator.shutdown()
            val remaining = (deadlineNanos - System.nanoTime()).coerceAtLeast(0)
            if (!pool.awaitTermination(remaining, TimeUnit.NANOSECONDS)) pool.shutdownNow()
        }
    }
}
