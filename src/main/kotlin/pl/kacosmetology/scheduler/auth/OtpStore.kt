package pl.kacosmetology.scheduler.auth

import java.security.SecureRandom
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

/** Redis-backed atomic OTP issuance, rolling accepted-issuance limits, and single-use verification. */
@Component
class OtpStore(
    private val redisTemplate: StringRedisTemplate,
    @Value($$"${otp.global-daily-sms-cap:0}") private val globalDailySmsCap: Long,
    environment: Environment,
) {
    init {
        require(globalDailySmsCap >= 0) { "OTP daily SMS cap must not be negative" }
        require(!environment.matchesProfiles("prod") || globalDailySmsCap > 0) {
            "Production requires a positive operator-configured OTP_GLOBAL_DAILY_SMS_CAP"
        }
    }

    private val random = SecureRandom()

    companion object {
        private val issueScript =
            RedisScript.of<List<*>>(
                """
                local t = redis.call('TIME')
                local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
                local cooldown = redis.call('PTTL', KEYS[2])
                if cooldown > 0 then return {0, math.ceil(cooldown / 1000)} end
                redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now - 300000)
                if redis.call('ZCARD', KEYS[3]) >= 3 then
                    local oldest = redis.call('ZRANGE', KEYS[3], 0, 0, 'WITHSCORES')
                    return {0, math.max(1, math.ceil((tonumber(oldest[2]) + 300000 - now) / 1000))}
                end
                local day = math.floor(tonumber(t[1]) / 86400)
                local dailyDay = tonumber(redis.call('HGET', KEYS[5], 'day'))
                local dailyCount = tonumber(redis.call('HGET', KEYS[5], 'count')) or 0
                if dailyDay ~= day then dailyCount = 0 end
                if tonumber(ARGV[3]) > 0 and dailyCount >= tonumber(ARGV[3]) then
                    return {0, (day + 1) * 86400 - tonumber(t[1])}
                end
                local code = ARGV[1]
                local previous = redis.call('GET', KEYS[4])
                if previous == code then code = string.format('%06d', (tonumber(code) + 1) % 1000000) end
                redis.call('SET', KEYS[1], code, 'PX', 300000)
                redis.call('SET', KEYS[2], '1', 'PX', 60000)
                redis.call('ZADD', KEYS[3], now, ARGV[2])
                redis.call('PEXPIRE', KEYS[3], 300000)
                redis.call('SET', KEYS[4], code, 'EX', 86400)
                redis.call('HSET', KEYS[5], 'day', day, 'count', dailyCount + 1)
                redis.call('EXPIRE', KEYS[5], 172800)
                return {1, code}
                """
                    .trimIndent(),
                List::class.java,
            )

        private val verifyScript =
            RedisScript.of<Long>(
                """
                local value = redis.call('GET', KEYS[1])
                if not value then return 0 end
                local separator = string.find(value, '|', 1, true)
                local code = separator and string.sub(value, 1, separator - 1) or value
                local failures = separator and tonumber(string.sub(value, separator + 1)) or 0
                if failures >= 3 then return 3 end
                if code == ARGV[1] then redis.call('DEL', KEYS[1]); return 1 end
                failures = failures + 1
                redis.call('SET', KEYS[1], code .. '|' .. failures, 'KEEPTTL')
                if failures >= 3 then return 3 end
                return 2
                """
                    .trimIndent(),
                Long::class.java,
            )
    }

    /** Issues a fresh code or rejects without consuming the accepted-phone or daily SMS allowance. */
    fun issueCode(phoneNumber: String): String {
        val candidate = random.nextInt(1_000_000).toString().padStart(6, '0')
        val result =
            checkNotNull(
                redisTemplate.execute(
                    issueScript,
                    listOf(
                        "otp:$phoneNumber",
                        "otp:cooldown:$phoneNumber",
                        "rate:sms:$phoneNumber",
                        "otp:previous:$phoneNumber",
                        "otp:global-daily",
                    ),
                    candidate,
                    UUID.randomUUID().toString(),
                    globalDailySmsCap.toString(),
                ),
            )
        if ((result[0] as Number).toLong() != 1L) {
            throw RateLimitExceededException(retryAfterSeconds = (result[1] as Number).toLong())
        }
        return result[1].toString()
    }

    /** Records at most three failures, preserves expiry, and consumes success in a single Redis operation. */
    fun verifyAndConsumeCode(phoneNumber: String, submittedCode: String): OtpVerificationResult {
        val result = checkNotNull(redisTemplate.execute(verifyScript, listOf("otp:$phoneNumber"), submittedCode))
        return when (result) {
            0L -> OtpVerificationResult.EXPIRED_OR_MISSING
            1L -> OtpVerificationResult.VERIFIED
            2L -> OtpVerificationResult.INVALID
            3L -> OtpVerificationResult.ATTEMPTS_EXCEEDED
            else -> error("Unexpected Redis OTP verification result")
        }
    }
}

/** Result of atomically checking a submitted one-time password. */
enum class OtpVerificationResult {
    VERIFIED,
    INVALID,
    EXPIRED_OR_MISSING,
    ATTEMPTS_EXCEEDED,
}
