package pl.kacosmetology.scheduler.auth

import java.time.Duration
import java.util.*
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

/** Atomic rolling request accounting based on Redis time, shared by authentication and booking limits. */
@Component
class RedisRollingRateLimiter(private val redisTemplate: StringRedisTemplate) {
    companion object {
        private val script =
            RedisScript.of(
                """
                local t = redis.call('TIME')
                local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
                local window = tonumber(ARGV[1])
                redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
                redis.call('ZADD', KEYS[1], now, ARGV[3])
                redis.call('PEXPIRE', KEYS[1], window)
                local count = redis.call('ZCARD', KEYS[1])
                if count <= tonumber(ARGV[2]) then return {1, 0} end
                local first = redis.call('ZRANGE', KEYS[1], count - tonumber(ARGV[2]),
                    count - tonumber(ARGV[2]), 'WITHSCORES')
                return {0, math.max(1, math.ceil((tonumber(first[2]) + window - now) / 1000))}
                """
                    .trimIndent(),
                List::class.java,
            )
    }

    /** Counts every request, including rejected requests; returns the earliest retry delay in seconds. */
    fun check(key: String, limit: Long, window: Duration): RateLimitDecision {
        require(limit > 0 && !window.isNegative && !window.isZero)
        val result =
            checkNotNull(
                redisTemplate.execute(
                    script,
                    listOf(key),
                    window.toMillis().toString(),
                    limit.toString(),
                    UUID.randomUUID().toString(),
                ),
            )
        return RateLimitDecision((result[0] as Number).toLong() == 1L, (result[1] as Number).toLong())
    }
}

/** Result of one atomic request-limit check. */
data class RateLimitDecision(val allowed: Boolean, val retryAfterSeconds: Long)
