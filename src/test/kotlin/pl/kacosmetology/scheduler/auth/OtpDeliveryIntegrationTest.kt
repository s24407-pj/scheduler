package pl.kacosmetology.scheduler.auth

import com.ninjasquad.springmockk.MockkBean
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.auth.sms.DailySmsBudget
import pl.kacosmetology.scheduler.auth.sms.SmsDeliveryResult
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.config.SmsWorkerProperties
import pl.kacosmetology.scheduler.config.SmsWorkers
import software.amazon.awssdk.services.s3.S3Client

/**
 * Exercises durable OTP intent, real Redis validity, committed claims and crash-state recovery without logging codes.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, OtpDeliveryIntegrationTest.TestBeans::class)
class OtpDeliveryIntegrationTest {
    @Autowired private lateinit var workers: SmsWorkers
    @Autowired private lateinit var auth: AuthService
    @Autowired private lateinit var deliveries: OtpDeliveryService
    @Autowired private lateinit var store: OtpStore
    @Autowired private lateinit var repository: OtpDeliveryRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var source: DataSource
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var manager: PlatformTransactionManager
    @Autowired private lateinit var clock: OtpDeliveryClock
    @Autowired private lateinit var provider: RecordingOtpProvider
    @MockkBean private lateinit var s3Client: S3Client
    private val now = Instant.parse("2027-06-01T07:00:00Z")
    private val phone = "+48880000001"
    private lateinit var transactions: TransactionTemplate

    @BeforeEach
    fun reset() {
        awaitWorkers()
        transactions = TransactionTemplate(manager)
        redis.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
        clock.set(now)
        provider.reset()
        deliveries.recoverAfterRestart()
    }

    @Test
    fun `rolled back OTP request persists no intent sends no SMS and creates no account or profile`() {
        assertThrows<IllegalStateException> {
            transactions.executeWithoutResult {
                auth.requestCode(RequestCodeRequest(phone), "ip")
                awaitWorkers()
                error("rollback")
            }
        }
        assertEquals(0, count("otp_deliveries"))
        assertEquals(0, provider.calls.get())
        assertNoIdentity()
    }

    @Test
    fun `committed intent and SENDING attempt are visible on a separate connection before provider call`() {
        provider.beforeSend = {
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT state,attempts,code IS NOT NULL FROM otp_deliveries").use { rows ->
                        assertTrue(rows.next())
                        assertEquals("SENDING", rows.getString(1))
                        assertEquals(1, rows.getInt(2))
                        assertTrue(rows.getBoolean(3))
                    }
                }
            }
        }
        auth.requestCode(RequestCodeRequest(phone), "ip")
        awaitWorkers()
        assertEquals("ACCEPTED", state())
        assertEquals(1, provider.calls.get())
        assertScrubbed()
        assertNoIdentity()
        val expiry = jdbc.queryForObject("SELECT expires_at FROM otp_deliveries", Timestamp::class.java)!!.toInstant()
        assertTrue(Duration.between(now, expiry).toMillis() in 298_000..300_000)
        dispatchAndAwait()
        assertEquals(1, provider.calls.get())
    }

    @Test
    fun `definite temporary retry reserves an extra global allowance without another phone issuance`() {
        provider.outcome = SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
        auth.requestCode(RequestCodeRequest(phone), "ip")
        awaitWorkers()
        assertEquals("TEMPORARY_FAILURE", state())
        assertEquals(1, provider.calls.get())
        assertEquals("1", redis.opsForHash<String, String>().get("otp:global-daily", "count"))
        clock.set(now.plusSeconds(31))
        provider.outcome = SmsDeliveryResult.ACCEPTED
        dispatchAndAwait()
        assertEquals("ACCEPTED", state())
        assertEquals(2, provider.calls.get())
        assertEquals("2", redis.opsForHash<String, String>().get("otp:global-daily", "count"))
        assertScrubbed()
    }

    @Test
    fun `one send daily budget permits pre reserved first attempt but rejects a temporary retry`() {
        provider.outcome = SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
        auth.requestCode(RequestCodeRequest(phone), "ip")
        awaitWorkers()
        assertEquals(1, provider.calls.get())
        val cappedRegistry = SimpleMeterRegistry()
        val cappedWorkers = SmsWorkers(SmsWorkerProperties(), cappedRegistry, clock)
        try {
            val cappedWorker =
                OtpDeliveryService(repository, store, provider, DailySmsBudget(redis, 1), manager, clock, cappedWorkers)
            cappedWorker.recoverAfterRestart()
            clock.set(now.plusSeconds(31))
            cappedWorker.dispatchDue()
            assertTrue(cappedWorkers.awaitIdle())
            assertEquals("DISCARDED", state())
            assertEquals(1, provider.calls.get())
            assertEquals("1", redis.opsForHash<String, String>().get("otp:global-daily", "count"))
            assertEquals(1L, redis.opsForZSet().zCard("rate:sms:$phone"))
            assertScrubbed()
        } finally {
            cappedWorkers.stop()
            cappedRegistry.close()
        }
    }

    @Test
    fun `first delivery on a new Redis UTC day renews allowance even if intent was staged later`() {
        queue(phone)
        redis.opsForValue().set("otp:delivery-day:$phone", "0", Duration.ofMinutes(5))
        dispatchAndAwait()
        assertEquals("ACCEPTED", state())
        assertEquals(1, provider.calls.get())
        assertEquals("2", redis.opsForHash<String, String>().get("otp:global-daily", "count"))
        assertEquals(1L, redis.opsForZSet().zCard("rate:sms:$phone"))
        assertScrubbed()
    }

    @Test
    fun `temporary retry is discarded at exact original expiry and plaintext is removed`() {
        provider.outcome = SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
        auth.requestCode(RequestCodeRequest(phone), "ip")
        awaitWorkers()
        val expiry = jdbc.queryForObject("SELECT expires_at FROM otp_deliveries", Timestamp::class.java)!!.toInstant()
        clock.set(expiry)
        dispatchAndAwait()
        assertEquals("DISCARDED", state())
        assertEquals(1, provider.calls.get())
        assertScrubbed()
        assertNoIdentity()
    }

    @Test
    fun `ambiguous provider response and unclassified exception are terminal and never blindly retried`() {
        provider.outcome = SmsDeliveryResult.AMBIGUOUS
        auth.requestCode(RequestCodeRequest(phone), "ip")
        awaitWorkers()
        assertEquals("AMBIGUOUS", state())
        assertScrubbed()
        clock.set(now.plusSeconds(31))
        dispatchAndAwait()
        assertEquals(1, provider.calls.get())
        provider.throwUnknown = true
        auth.requestCode(RequestCodeRequest("+48880000002"), "other")
        awaitWorkers()
        assertEquals(2, countState("AMBIGUOUS"))
        clock.set(now.plusSeconds(62))
        dispatchAndAwait()
        assertEquals(2, provider.calls.get())
        assertScrubbed()
    }

    @Test
    fun `restart turns unknown in flight send ambiguous but preserves live pending delivery`() {
        queue(phone)
        jdbc.update("UPDATE otp_deliveries SET state='SENDING',attempts=1")
        queue("+48880000002")
        deliveries.recoverAfterRestart()
        dispatchAndAwait()
        assertEquals(1, countState("AMBIGUOUS"))
        assertEquals(1, countState("ACCEPTED"))
        assertEquals(1, provider.calls.get())
        assertScrubbed()
    }

    @Test
    fun `expired in flight send becomes ambiguous and scrubs secret without a restart or resend`() {
        queue(phone)
        jdbc.update("UPDATE otp_deliveries SET state='SENDING',attempts=1")
        clock.set(now.plusSeconds(301))
        dispatchAndAwait()
        assertEquals("AMBIGUOUS", state())
        assertScrubbed()
        assertEquals(0, provider.calls.get())
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM otp_deliveries", Int::class.java))
        dispatchAndAwait()
        assertEquals(0, provider.calls.get())
    }

    @Test
    fun `duplicate issuance callback keeps one durable delivery identity and one provider attempt`() {
        val code = store.issueCode(phone)
        val event = OtpIssuedEvent(phone, code)
        transactions.executeWithoutResult {
            deliveries.stage(event)
            deliveries.stage(event)
        }
        assertEquals(1, count("otp_deliveries"))
        dispatchAndAwait()
        assertEquals(1, provider.calls.get())
        assertEquals("ACCEPTED", state())
        assertScrubbed()
    }

    @Test
    fun `replacement invalidates pending delivery and delayed old event cannot invalidate the newer code`() {
        val old = queue(phone)
        redis.delete("otp:cooldown:$phone")
        queue(phone)
        transactions.executeWithoutResult { deliveries.stage(OtpIssuedEvent(phone, old)) }
        assertEquals(2, countState("DISCARDED"))
        assertEquals(1, countState("PENDING"))
        dispatchAndAwait()
        assertEquals(1, provider.calls.get())
        assertEquals(1, countState("ACCEPTED"))
        assertScrubbed()
        assertNoIdentity()
    }

    @Test
    fun `consumed Redis code invalidates unsent intent even without a consumption event`() {
        val code = queue(phone)
        assertEquals(OtpVerificationResult.VERIFIED, store.verifyAndConsumeCode(phone, code))
        dispatchAndAwait()
        assertEquals("DISCARDED", state())
        assertEquals(0, provider.calls.get())
        assertScrubbed()
        assertNoIdentity()
    }

    @Test
    fun `successful verification atomically scrubs pending delivery through a consumed event`() {
        provider.outcome = SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
        auth.requestCode(RequestCodeRequest(phone), "ip")
        awaitWorkers()
        val code = requireNotNull(redis.opsForValue().get("otp:$phone"))
        auth.verifyCode(VerifyCodeRequest(phone, code), "ip")
        assertEquals("DISCARDED", state())
        assertScrubbed()
        clock.set(now.plusSeconds(31))
        dispatchAndAwait()
        assertEquals(1, provider.calls.get())
        assertEquals(1, count("customer_accounts"))
        assertEquals(0, count("company_customers"))
    }

    @Test
    fun `expired Redis state or exhausted verification allowance makes pending code undeliverable`() {
        queue(phone)
        redis.delete("otp:$phone")
        dispatchAndAwait()
        assertEquals("DISCARDED", state())
        val other = "+48880000002"
        val code = queue(other)
        val wrong = if (code == "000000") "000001" else "000000"
        repeat(3) { store.verifyAndConsumeCode(other, wrong) }
        dispatchAndAwait()
        assertEquals(2, countState("DISCARDED"))
        assertEquals(0, provider.calls.get())
        assertScrubbed()
    }

    @Test
    fun `two OTP slots run concurrently and waiting expired code is never sent`() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        provider.beforeSend = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        repeat(3) { queue("+4888030000$it") }
        try {
            deliveries.dispatchDue()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(100) { deliveries.dispatchDue() }
            assertEquals(2, provider.calls.get())
            assertEquals(2, countState("SENDING"))
            assertEquals(1, countState("PENDING"))
            clock.set(now.plusSeconds(301))
        } finally {
            release.countDown()
            awaitWorkers()
        }
        assertEquals(2, countState("ACCEPTED"))
        assertEquals(1, countState("DISCARDED"))
        assertEquals(2, provider.calls.get())
        assertScrubbed()
    }

    @Test
    fun `operator resolution requires a stale unchanged OTP attempt and scrubs it without retry`() {
        queue(phone)
        jdbc.update("UPDATE otp_deliveries SET state='SENDING',attempts=1,updated_at=CURRENT_TIMESTAMP")
        val id = jdbc.queryForObject("SELECT id FROM otp_deliveries", java.util.UUID::class.java)!!
        val recent = jdbc.queryForObject("SELECT updated_at FROM otp_deliveries", Timestamp::class.java)!!.toInstant()
        fun resolve(expected: Instant) {
            val sql =
                Files.readString(Path.of("scripts/ops/resolve-otp-sending.sql"))
                    .lineSequence()
                    .filterNot { it.startsWith("\\") }
                    .joinToString("\n")
                    .replace(":'delivery_id'", "'$id'")
                    .replace(":'expected_updated_at'", "'$expected'")
            jdbc.execute(sql)
        }
        resolve(recent)
        assertEquals("SENDING", state(), "An active-looking attempt must not be resolved")
        jdbc.update("UPDATE otp_deliveries SET updated_at=CURRENT_TIMESTAMP - INTERVAL '3 minutes'")
        resolve(recent)
        assertEquals("SENDING", state(), "A changed row must not be resolved using an outdated snapshot")
        val stale = jdbc.queryForObject("SELECT updated_at FROM otp_deliveries", Timestamp::class.java)!!.toInstant()
        assertEquals(1L, repository.sendingStats().count)
        resolve(stale)
        assertEquals("AMBIGUOUS", state())
        assertScrubbed()
        assertEquals(0L, repository.sendingStats().count)
        resolve(stale)
        dispatchAndAwait()
        assertEquals(0, provider.calls.get())
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM otp_deliveries", Int::class.java))
    }

    @AfterEach
    fun awaitWorkers() {
        assertTrue(workers.awaitIdle(), "SMS workers did not settle")
    }

    private fun dispatchAndAwait() {
        deliveries.dispatchDue()
        awaitWorkers()
    }

    private fun queue(phone: String): String {
        val code = store.issueCode(phone)
        transactions.executeWithoutResult { deliveries.stage(OtpIssuedEvent(phone, code)) }
        return code
    }

    private fun count(table: String): Int = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun countState(state: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM otp_deliveries WHERE state=?", Int::class.java, state)!!

    private fun state(): String =
        jdbc.queryForObject("SELECT state FROM otp_deliveries ORDER BY created_at DESC LIMIT 1", String::class.java)!!

    private fun assertScrubbed() {
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM otp_deliveries WHERE code IS NOT NULL", Int::class.java),
        )
    }

    private fun assertNoIdentity() {
        assertEquals(0, count("customer_accounts"))
        assertEquals(0, count("company_customers"))
    }

    @TestConfiguration
    class TestBeans {
        @Bean @Primary fun otpDeliveryClock(): OtpDeliveryClock = OtpDeliveryClock()

        @Bean @Primary fun recordingOtpProvider(): RecordingOtpProvider = RecordingOtpProvider()
    }
}

/** Secret-free provider fake: counts calls and classifications without retaining codes or phone data. */
class RecordingOtpProvider : SmsSender {
    val calls = AtomicInteger()
    var outcome = SmsDeliveryResult.ACCEPTED
    var throwUnknown = false
    var beforeSend: () -> Unit = {}

    /** Resets behavior between independent delivery scenarios. */
    fun reset() {
        calls.set(0)
        outcome = SmsDeliveryResult.ACCEPTED
        throwUnknown = false
        beforeSend = {}
    }

    override fun sendOtp(phoneNumber: String, code: String) = Unit

    override fun sendMessage(phoneNumber: String, message: String) = Unit

    override fun deliverOtp(phoneNumber: String, code: String): SmsDeliveryResult {
        calls.incrementAndGet()
        beforeSend()
        if (throwUnknown) error("Unknown provider outcome")
        return outcome
    }
}

/** Mutable UTC test clock advances retry and expiration boundaries independently of Warsaw/JVM local time. */
class OtpDeliveryClock : Clock() {
    private val now = AtomicReference(Instant.parse("2027-06-01T07:00:00Z"))

    /** Changes the test's authoritative instant. */
    fun set(value: Instant) {
        now.set(value)
    }

    override fun instant(): Instant = now.get()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
}
