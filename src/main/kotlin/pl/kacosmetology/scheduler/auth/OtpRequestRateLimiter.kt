package pl.kacosmetology.scheduler.auth

import java.time.Duration
import org.springframework.stereotype.Component

/** Counts all OTP issuance requests per IP, including cooldown-denied requests. */
@Component
class OtpRequestRateLimiter(private val rollingRateLimiter: RedisRollingRateLimiter) {
    /** Applies the rolling ten-request, fifteen-minute issuance limit. */
    fun check(ip: String): RateLimitDecision =
        rollingRateLimiter.check("rate:otp-request:$ip", 10, Duration.ofMinutes(15))

    /** Returns whether this counted request is allowed. */
    fun checkAndIncrement(ip: String): Boolean = check(ip).allowed
}
