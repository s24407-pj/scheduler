package pl.kacosmetology.scheduler.auth

import java.time.Duration
import org.springframework.stereotype.Component

/** Counts OTP verification requests in a rolling five-minute window per client IP. */
@Component
class OtpVerificationRateLimiter(private val rollingRateLimiter: RedisRollingRateLimiter) {
    /** Applies the rolling ten-request verification limit. */
    fun check(ip: String): RateLimitDecision =
        rollingRateLimiter.check("rate:otp-verify:$ip", 10, Duration.ofMinutes(5))

    /** Returns whether this counted request is allowed. */
    fun checkAndIncrement(ip: String): Boolean = check(ip).allowed
}
