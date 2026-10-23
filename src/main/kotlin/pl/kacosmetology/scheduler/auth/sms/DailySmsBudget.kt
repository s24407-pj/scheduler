package pl.kacosmetology.scheduler.auth.sms

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import pl.kacosmetology.scheduler.auth.RateLimitDecision

/** Shared UTC daily send reservations, using the same atomic Redis counter as OTP issuance. */
@Component
class DailySmsBudget(
    private val redis: StringRedisTemplate,
    @Value($$"${otp.global-daily-sms-cap:0}") private val cap: Long,
) {
    companion object {
        /** OTP issuance reserves against this exact key within its issuance Lua script. */
        const val KEY = "otp:global-daily"
        private val reserveScript =
            RedisScript.of(
                """
                local t = redis.call('TIME')
                local day = math.floor(tonumber(t[1]) / 86400)
                local previous = tonumber(redis.call('HGET', KEYS[1], 'day'))
                local count = tonumber(redis.call('HGET', KEYS[1], 'count')) or 0
                if previous ~= day then count = 0 end
                if tonumber(ARGV[1]) > 0 and count >= tonumber(ARGV[1]) then
                    return {0, (day + 1) * 86400 - tonumber(t[1])}
                end
                redis.call('HSET', KEYS[1], 'day', day, 'count', count + 1)
                redis.call('EXPIRE', KEYS[1], 172800)
                return {1, 0}
                """
                    .trimIndent(),
                List::class.java,
            )
    }

    /** Reserves one provider attempt; failed and ambiguous attempts do not refund the budget. */
    fun reserve(): RateLimitDecision {
        val result = checkNotNull(redis.execute(reserveScript, listOf(KEY), cap.toString()))
        return RateLimitDecision((result[0] as Number).toLong() == 1L, (result[1] as Number).toLong())
    }
}
