package pl.kacosmetology.scheduler.auth.sms

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import pl.kacosmetology.scheduler.config.SmsWorkerProperties
import pl.kacosmetology.scheduler.config.SmsWorkers
import pl.kacosmetology.scheduler.testId

/** Controlled provider delays prove bounded admission and pool isolation without waiting for real SMS timeouts. */
class SmsWorkerTest {
    private val now = Instant.parse("2027-06-01T07:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `thirty delayed sends drain in eight waves with at most four admitted references`() {
        val registry = SimpleMeterRegistry()
        val pending = ConcurrentHashMap.newKeySet<SmsWork>()
        repeat(30) { pending.add(SmsWork(testId(it.toLong()), "lane-$it", now.minusSeconds(2))) }
        val started = LinkedBlockingQueue<java.util.UUID>()
        val permits = Semaphore(0)
        val running = AtomicInteger()
        val maximum = AtomicInteger()
        val worker =
            SmsWorker(
                "notification",
                4,
                registry,
                clock,
                { excluded, limit -> pending.filter { it.lane !in excluded }.sortedBy { it.id }.take(limit) },
                { id ->
                    val active = running.incrementAndGet()
                    maximum.accumulateAndGet(active, ::maxOf)
                    started.add(id)
                    try {
                        check(permits.tryAcquire(10, TimeUnit.SECONDS))
                    } finally {
                        running.decrementAndGet()
                    }
                    pending.removeIf { it.id == id }
                },
                { pending.size.toLong() },
            )
        try {
            worker.wake()
            for (wave in 0 until 8) {
                val batch = minOf(4, 30 - wave * 4)
                repeat(batch) { assertNotNull(started.poll(5, TimeUnit.SECONDS)) }
                assertTrue(registry.get("scheduler.sms.worker.admitted").gauge().value() <= 4.0)
                repeat(1000) { worker.wake() }
                assertNull(started.poll(50, TimeUnit.MILLISECONDS), "Admission exceeded pool size")
                permits.release(batch)
            }
            assertTrue(worker.awaitIdle(Duration.ofSeconds(5)))
            assertEquals(4, maximum.get())
            assertTrue(pending.isEmpty())
            assertEquals(30L, registry.get("scheduler.sms.worker.wait").timer().count())
            assertEquals(0.0, registry.get("scheduler.sms.worker.pending").gauge().value())
        } finally {
            permits.release(30)
            worker.stopAdmission()
            worker.awaitTermination(System.nanoTime() + Duration.ofSeconds(5).toNanos())
            registry.close()
        }
    }

    @Test
    fun `saturated notifications cannot occupy OTP workers and shutdown rejects new work`() {
        val registry = SimpleMeterRegistry()
        val workers = SmsWorkers(SmsWorkerProperties(), registry, clock)
        val blocked = CountDownLatch(4)
        val release = CountDownLatch(1)
        val remaining = ConcurrentHashMap.newKeySet<SmsWork>()
        repeat(5) { remaining.add(SmsWork(testId(it.toLong()), "lane-$it", now)) }
        val notification =
            workers.create(
                "notification",
                { excluded, limit -> remaining.filter { it.lane !in excluded }.take(limit) },
                { id ->
                    blocked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    remaining.removeIf { it.id == id }
                },
                { remaining.size.toLong() },
            )
        val otpPending = ConcurrentHashMap.newKeySet<SmsWork>()
        otpPending.add(SmsWork(testId(100), "otp-lane", now))
        val otpSent = CountDownLatch(1)
        val otp =
            workers.create(
                "otp",
                { _, limit -> otpPending.toList().take(limit) },
                {
                    otpPending.clear()
                    otpSent.countDown()
                },
                { otpPending.size.toLong() },
            )
        try {
            notification.wake()
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            otp.wake()
            assertTrue(otpSent.await(5, TimeUnit.SECONDS))
            assertEquals(1L, release.count)
            notification.stopAdmission()
            release.countDown()
            workers.stop()
            assertEquals(1, remaining.size, "Unadmitted durable work must remain queued")
            otpPending.add(SmsWork(testId(101), "later", now))
            otp.wake()
            assertEquals(1, otpPending.size)
        } finally {
            release.countDown()
            workers.stop()
            registry.close()
        }
    }

    @Test
    fun `failed preparation releases capacity and waits for another wake instead of a busy retry loop`() {
        val registry = SimpleMeterRegistry()
        val calls = AtomicInteger()
        val work = SmsWork(testId(1), "lane", now)
        val worker =
            SmsWorker(
                "otp",
                2,
                registry,
                clock,
                { _, limit -> listOf(work).take(limit) },
                {
                    calls.incrementAndGet()
                    error("database unavailable")
                },
                { 1 },
            )
        try {
            worker.wake()
            assertTrue(worker.awaitIdle(Duration.ofSeconds(5)))
            assertEquals(1, calls.get())
            worker.wake()
            assertTrue(worker.awaitIdle(Duration.ofSeconds(5)))
            assertEquals(2, calls.get())
        } finally {
            worker.stopAdmission()
            worker.awaitTermination(System.nanoTime() + Duration.ofSeconds(5).toNanos())
            registry.close()
        }
    }
}
