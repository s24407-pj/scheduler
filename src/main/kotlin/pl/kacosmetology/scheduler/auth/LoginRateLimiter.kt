package pl.kacosmetology.scheduler.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
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

        private val incrWithExpireScript = RedisScript.of<Long>(
            """
            local v = redis.call('INCR', KEYS[1])
            if v == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return v
            """.trimIndent(),
            Long::class.java
        )
    }

    /**
     * Checks and increments the login attempt counter for [ip].
     * Returns `true` if the request is within limits, `false` if exceeded.
     * The counter resets automatically after the configured time window.
     */
    fun checkAndIncrement(ip: String): Boolean {
        val key = "$KEY_PREFIX$ip"
        val count = redisTemplate.execute(
            incrWithExpireScript,
            listOf(key),
            (rateWindowMinutes * 60).toString()
        ) ?: 1L
        return count <= maxAttempts
    }
}
