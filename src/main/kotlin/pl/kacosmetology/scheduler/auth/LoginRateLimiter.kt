package pl.kacosmetology.scheduler.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/** Redis-backed rate limiter for staff login attempts, keyed by client IP address. */
@Component
class LoginRateLimiter(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${login.max-attempts}") private val maxAttempts: Long,
    @Value("\${login.rate-window-minutes}") private val rateWindowMinutes: Long
) {

    companion object {
        private const val KEY_PREFIX = "rate:login:"
    }

    /**
     * Checks and increments the login attempt counter for [ip].
     * Returns `true` if the request is within limits, `false` if exceeded.
     * The counter resets automatically after the configured time window.
     */
    fun checkAndIncrement(ip: String): Boolean {
        val key = "$KEY_PREFIX$ip"
        val count = redisTemplate.opsForValue().increment(key) ?: 1L
        if (count == 1L) {
            redisTemplate.expire(key, Duration.ofMinutes(rateWindowMinutes))
        }
        return count <= maxAttempts
    }
}
