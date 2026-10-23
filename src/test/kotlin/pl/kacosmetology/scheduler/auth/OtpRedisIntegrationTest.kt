package pl.kacosmetology.scheduler.auth

import com.ninjasquad.springmockk.MockkBean
import io.mockk.*
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.mock.env.MockEnvironment
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.sms.SmsDeliveryResult
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.config.SmsWorkers
import software.amazon.awssdk.services.s3.S3Client

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class OtpRedisIntegrationTest {
    @Autowired private lateinit var workers: SmsWorkers
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var store: OtpStore
    @Autowired private lateinit var requests: OtpRequestRateLimiter
    @Autowired private lateinit var verifications: OtpVerificationRateLimiter
    @Autowired private lateinit var rolling: RedisRollingRateLimiter
    @Autowired private lateinit var authService: AuthService
    @Autowired private lateinit var deliveries: OtpDeliveryService
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @MockkBean private lateinit var smsSender: SmsSender
    @MockkBean private lateinit var s3Client: S3Client
    private val phone = "+48111222333"

    @BeforeEach
    fun reset() {
        awaitWorkers()
        redis.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
        clearMocks(smsSender)
        every { smsSender.sendOtp(any(), any()) } just Runs
        every { smsSender.deliverOtp(any(), any()) } answers
            {
                smsSender.sendOtp(firstArg(), secondArg())
                SmsDeliveryResult.ACCEPTED
            }
        deliveries.recoverAfterRestart()
    }

    @Test
    fun `issuance has exact code lifetime cooldown and accepted rolling phone allowance`() {
        val first = store.issueCode(phone)
        assertTrue(first.matches(Regex("[0-9]{6}")))
        assertTrue(redis.getExpire("otp:$phone") in 299L..300L)
        val cooldown = assertThrows<RateLimitExceededException> { store.issueCode(phone) }
        assertTrue(cooldown.retryAfterSeconds in 59L..60L)
        assertEquals(1L, redis.opsForZSet().zCard("rate:sms:$phone"))
        var previous = first
        repeat(2) {
            redis.delete("otp:cooldown:$phone")
            val replacement = store.issueCode(phone)
            assertNotEquals(previous, replacement)
            previous = replacement
        }
        redis.delete("otp:cooldown:$phone")
        assertThrows<RateLimitExceededException> { store.issueCode(phone) }
        assertEquals(3L, redis.opsForZSet().zCard("rate:sms:$phone"))
        expireWindow("rate:sms:$phone", 300_000)
        assertNotEquals(previous, store.issueCode(phone))
    }

    @Test
    fun `three failures lock code and replacement restores fresh allowance and expiry`() {
        val code = store.issueCode(phone)
        val wrong = if (code == "000000") "000001" else "000000"
        redis.expire("otp:$phone", Duration.ofSeconds(60))
        assertEquals(OtpVerificationResult.INVALID, store.verifyAndConsumeCode(phone, wrong))
        assertEquals(OtpVerificationResult.INVALID, store.verifyAndConsumeCode(phone, wrong))
        assertEquals(OtpVerificationResult.ATTEMPTS_EXCEEDED, store.verifyAndConsumeCode(phone, wrong))
        assertEquals(OtpVerificationResult.ATTEMPTS_EXCEEDED, store.verifyAndConsumeCode(phone, code))
        assertTrue(redis.getExpire("otp:$phone") <= 60)
        redis.delete("otp:cooldown:$phone")
        val replacement = store.issueCode(phone)
        assertNotEquals(code, replacement)
        assertTrue(redis.getExpire("otp:$phone") in 299L..300L)
        assertEquals(OtpVerificationResult.INVALID, store.verifyAndConsumeCode(phone, code))
        assertEquals(OtpVerificationResult.VERIFIED, store.verifyAndConsumeCode(phone, replacement))
        assertEquals(OtpVerificationResult.EXPIRED_OR_MISSING, store.verifyAndConsumeCode(phone, replacement))
    }

    @Test
    fun `consumption and replacement are atomic under simultaneous callers`() {
        val code = store.issueCode(phone)
        val consumed = concurrently(12) { store.verifyAndConsumeCode(phone, code) }
        assertEquals(1, consumed.count { it == OtpVerificationResult.VERIFIED })
        redis.delete("otp:cooldown:$phone")
        val issued = concurrently(12) { runCatching { store.issueCode(phone) }.getOrNull() }
        assertEquals(1, issued.count { it != null })
        assertNotEquals(code, issued.single { it != null })
        assertEquals(2L, redis.opsForZSet().zCard("rate:sms:$phone"))
    }

    @Test
    fun `cooldown denials count against IP requests but not accepted phone issuance`() {
        repeat(10) { index ->
            assertTrue(requests.check("ip").allowed)
            if (index == 0) store.issueCode(phone)
            else assertThrows<RateLimitExceededException> { store.issueCode(phone) }
        }
        val denied = requests.check("ip")
        assertFalse(denied.allowed)
        assertTrue(denied.retryAfterSeconds in 899L..900L)
        assertEquals(11L, redis.opsForZSet().zCard("rate:otp-request:ip"))
        assertEquals(1L, redis.opsForZSet().zCard("rate:sms:$phone"))
    }

    @Test
    fun `rolling IP windows age individual requests instead of resetting fixed buckets`() {
        repeat(10) { assertTrue(verifications.check("ip").allowed) }
        expireWindow("rate:otp-verify:ip", 300_000, oneOnly = true)
        assertTrue(verifications.check("ip").allowed)
        assertFalse(verifications.check("ip").allowed)
        expireWindow("rate:otp-verify:ip", 300_000)
        assertTrue(verifications.check("ip").allowed)
        repeat(10) { assertTrue(requests.check("other").allowed) }
        expireWindow("rate:otp-request:other", 900_000)
        assertTrue(requests.check("other").allowed)
    }

    @Test
    fun `rolling primitive counts concurrent rejected requests and preserves independent accounts`() {
        val results = concurrently(20) { rolling.check("rate:booking:account", 10, Duration.ofMinutes(1)) }
        assertEquals(10, results.count { it.allowed })
        assertEquals(20L, redis.opsForZSet().zCard("rate:booking:account"))
        assertTrue(rolling.check("rate:booking:other", 10, Duration.ofMinutes(1)).allowed)
    }

    @Test
    fun `global daily cap is atomic and resets only on next UTC day`() {
        val capped = OtpStore(redis, 2, MockEnvironment())
        val results = concurrently(10) { index -> runCatching { capped.issueCode("+4890000000$index") }.isSuccess }
        assertEquals(2, results.count { it })
        assertEquals("2", redis.opsForHash<String, String>().get("otp:global-daily", "count"))
        redis.opsForHash<String, String>().put("otp:global-daily", "day", "0")
        capped.issueCode("+48888888888")
        assertEquals("1", redis.opsForHash<String, String>().get("otp:global-daily", "count"))
    }

    @Test
    fun `production refuses absent or unlimited daily cap`() {
        val prod = MockEnvironment().withProperty("spring.profiles.active", "prod")
        prod.setActiveProfiles("prod")
        assertThrows<IllegalArgumentException> { OtpStore(redis, 0, prod) }
        assertThrows<IllegalArgumentException> { OtpStore(redis, -1, prod) }
        OtpStore(redis, 100, prod)
    }

    @Test
    fun `rollback never sends OTP SMS and provider failure never fails committed request`() {
        assertThrows<IllegalStateException> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                authService.requestCode(RequestCodeRequest(phone), "ip")
                error("rollback")
            }
        }
        verify(exactly = 0) { smsSender.sendOtp(any(), any()) }
        every { smsSender.sendOtp(any(), any()) } throws IllegalStateException("provider rejected")
        authService.requestCode(RequestCodeRequest("+48444444444"), "other")
        awaitWorkers()
        verify(exactly = 1) { smsSender.sendOtp("+48444444444", any()) }
    }

    @AfterEach
    fun awaitWorkers() {
        assertTrue(workers.awaitIdle(), "SMS workers did not settle")
    }

    private fun expireWindow(key: String, window: Long, oneOnly: Boolean = false) {
        redis.execute(
            RedisScript.of(
                """
                local t = redis.call('TIME')
                local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
                local members = redis.call('ZRANGE', KEYS[1], 0, ARGV[2])
                for _, member in ipairs(members) do redis.call('ZADD', KEYS[1], now - tonumber(ARGV[1]), member) end
                return #members
                """
                    .trimIndent(),
                Long::class.java,
            ),
            listOf(key),
            window.toString(),
            if (oneOnly) "0" else "-1",
        )
    }

    private fun <T> concurrently(count: Int, action: (Int) -> T): List<T> {
        val executor = Executors.newFixedThreadPool(count)
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        try {
            val futures =
                List(count) { index ->
                    executor.submit<T> {
                        ready.countDown()
                        start.await()
                        action(index)
                    }
                }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
