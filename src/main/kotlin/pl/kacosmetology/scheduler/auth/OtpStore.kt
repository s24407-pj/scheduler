package pl.kacosmetology.scheduler.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/** Redis-backed store for OTP codes with built-in TTL expiration and rate limiting. */
@Component
class OtpStore(
    private val redisTemplate: StringRedisTemplate,
    @Value($$"${otp.ttl-minutes}") private val otpTtlMinutes: Long,
    @Value($$"${otp.max-attempts}") private val maxAttempts: Long,
    @Value($$"${otp.rate-window-minutes}") private val rateWindowMinutes: Long
) {

    companion object {
        private const val OTP_KEY_PREFIX = "otp:"
        private const val RATE_KEY_PREFIX = "rate:sms:"
    }

    /** Stores an OTP code in Redis with automatic TTL expiration. */
    fun saveCode(phoneNumber: String, code: String) {
        redisTemplate.opsForValue().set("$OTP_KEY_PREFIX$phoneNumber", code, Duration.ofMinutes(otpTtlMinutes))
    }

    /** Retrieves a stored OTP code. Returns null if expired or not found. */
    fun getCode(phoneNumber: String): String? {
        return redisTemplate.opsForValue().get("$OTP_KEY_PREFIX$phoneNumber")
    }

    /** Deletes an OTP code after successful verification. */
    fun deleteCode(phoneNumber: String) {
        redisTemplate.delete("$OTP_KEY_PREFIX$phoneNumber")
    }

    /**
     * Checks and increments the SMS rate limit counter for a phone number.
     * Returns `true` if the request is within limits, `false` if exceeded.
     * The counter resets automatically after the configured time window.
     */
    fun checkAndIncrementRateLimit(phoneNumber: String): Boolean {
        val key = "$RATE_KEY_PREFIX$phoneNumber"
        val currentCount = redisTemplate.opsForValue().increment(key) ?: 1

        if (currentCount == 1L) {
            redisTemplate.expire(key, Duration.ofMinutes(rateWindowMinutes))
        }

        return currentCount <= maxAttempts
    }
}
