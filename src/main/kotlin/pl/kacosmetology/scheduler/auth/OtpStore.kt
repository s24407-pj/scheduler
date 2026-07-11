package pl.kacosmetology.scheduler.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Duration

/** Redis-backed store for OTP codes with built-in TTL expiration and rate limiting. */
@Component
class OtpStore(
    private val redisTemplate: StringRedisTemplate,
    @Value($$"${otp.ttl-minutes}") private val otpTtlMinutes: Long,
    @Value($$"${otp.max-attempts}") private val maxAttempts: Long,
    @Value($$"${otp.rate-window-minutes}") private val rateWindowMinutes: Long,
    @Value($$"${otp.verification-max-attempts}") private val verificationMaxAttempts: Long
) {

    companion object {
        private const val OTP_KEY_PREFIX = "otp:"
        private const val RATE_KEY_PREFIX = "rate:sms:"

        private val incrWithExpireScript = RedisScript.of<Long>(
            """
            local v = redis.call('INCR', KEYS[1])
            if v == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return v
            """.trimIndent(),
            Long::class.java
        )

        private val verifyAndConsumeScript = RedisScript.of<Long>(
            """
            local value = redis.call('GET', KEYS[1])
            if not value then return 0 end

            local separator = string.find(value, '|', 1, true)
            local storedCode = value
            local failedAttempts = 0
            if separator then
                storedCode = string.sub(value, 1, separator - 1)
                failedAttempts = tonumber(string.sub(value, separator + 1)) or 0
            end

            local maxAttempts = tonumber(ARGV[2])
            if failedAttempts >= maxAttempts then return 3 end

            if storedCode == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 1
            end

            failedAttempts = failedAttempts + 1
            redis.call('SET', KEYS[1], storedCode .. '|' .. failedAttempts, 'KEEPTTL')
            if failedAttempts >= maxAttempts then return 3 end
            return 2
            """.trimIndent(),
            Long::class.java
        )
    }

    /** Stores an OTP code in Redis with automatic TTL expiration. */
    fun saveCode(phoneNumber: String, code: String) {
        redisTemplate.opsForValue().set("$OTP_KEY_PREFIX$phoneNumber", code, Duration.ofMinutes(otpTtlMinutes))
    }

    /** Atomically verifies an OTP, records failures, and consumes a successful code. */
    fun verifyAndConsumeCode(phoneNumber: String, submittedCode: String): OtpVerificationResult {
        val result = checkNotNull(
            redisTemplate.execute(
                verifyAndConsumeScript,
                listOf("$OTP_KEY_PREFIX$phoneNumber"),
                submittedCode,
                verificationMaxAttempts.toString()
            )
        ) { "Redis OTP verification returned no result" }

        return when (result) {
            0L -> OtpVerificationResult.EXPIRED_OR_MISSING
            1L -> OtpVerificationResult.VERIFIED
            2L -> OtpVerificationResult.INVALID
            3L -> OtpVerificationResult.ATTEMPTS_EXCEEDED
            else -> error("Unexpected Redis OTP verification result: $result")
        }
    }

    /**
     * Checks and increments the SMS rate limit counter for a phone number.
     * Returns `true` if the request is within limits, `false` if exceeded.
     * The counter resets automatically after the configured time window.
     */
    fun checkAndIncrementRateLimit(phoneNumber: String): Boolean {
        val key = "$RATE_KEY_PREFIX$phoneNumber"
        val currentCount = redisTemplate.execute(
            incrWithExpireScript,
            listOf(key),
            (rateWindowMinutes * 60).toString()
        ) ?: 1L
        return currentCount <= maxAttempts
    }
}

/** Result of atomically checking a submitted one-time password. */
enum class OtpVerificationResult {
    VERIFIED,
    INVALID,
    EXPIRED_OR_MISSING,
    ATTEMPTS_EXCEEDED
}
