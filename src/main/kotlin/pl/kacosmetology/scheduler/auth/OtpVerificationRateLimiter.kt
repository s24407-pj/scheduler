package pl.kacosmetology.scheduler.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

/** Redis-backed rate limiter for OTP verification requests, keyed by client IP address. */
@Component
class OtpVerificationRateLimiter(
    private val redisTemplate: StringRedisTemplate,
    @Value($$"${otp.verification-ip-max-attempts}") private val maxAttempts: Long,
    @Value($$"${otp.verification-ip-rate-window-minutes}") private val rateWindowMinutes: Long
) {

    companion object {
        private const val KEY_PREFIX = "rate:otp-verify:"

        private val incrWithExpireScript = RedisScript.of<Long>(
            """
            local v = redis.call('INCR', KEYS[1])
            if v == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return v
            """.trimIndent(),
            Long::class.java
        )
    }

    /** Increments the counter for [ip] and returns whether the request remains within the configured limit. */
    fun checkAndIncrement(ip: String): Boolean {
        val count = redisTemplate.execute(
            incrWithExpireScript,
            listOf("$KEY_PREFIX$ip"),
            (rateWindowMinutes * 60).toString()
        ) ?: 1L
        return count <= maxAttempts
    }
}
