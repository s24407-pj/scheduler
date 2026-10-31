package pl.kacosmetology.scheduler.auth

import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import tools.jackson.databind.ObjectMapper

enum class TotpGrantKind {
    VERIFY,
    SETUP,
    RECOVER,
    CONFIRM,
}

/** No secret-bearing object is ever placed in SecurityContext or serialized to a login response. */
data class TotpGrant(
    val account: VerifiedStaffAccount,
    val browser: String,
    val kind: TotpGrantKind,
    val expiresAt: Instant,
    val secret: String? = null,
    val recoveryCodes: List<String> = emptyList(),
)

@Component
class TotpGrantStore(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val cipher: TotpCipher,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    fun issue(grant: TotpGrant): String {
        val ttl = Duration.between(clock.instant(), grant.expiresAt)
        if (ttl.isNegative || ttl.isZero) expired()
        while (true) {
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
            val key = key(token)
            if (
                redis.opsForValue().setIfAbsent(key, cipher.encrypt(mapper.writeValueAsString(grant), key), ttl) == true
            )
                return token
        }
    }

    fun read(token: String, browser: String?, ip: String): TotpGrant {
        admitIp(ip)
        val key = key(token)
        val value = redis.opsForValue().get(key) ?: expired()
        val grant = mapper.readValue(cipher.decrypt(value, key), TotpGrant::class.java)
        if (
            browser == null ||
                grant.browser != StaffLoginSelectionStore.hash(browser) ||
                !clock.instant().isBefore(grant.expiresAt)
        )
            expired()
        return grant
    }

    /** Counts attempts before expensive verification; TTLs are set atomically and Redis failures close the gate. */
    fun verificationAttempt(token: String, account: java.util.UUID) {
        limit("account:$account", 10, 300)
        limit("grant:" + StaffLoginSelectionStore.hash(token), 5, 600)
    }

    fun admitIp(ip: String) {
        limit("ip:" + StaffLoginSelectionStore.hash(ip), 30, 300)
    }

    /** Successful proofs release their reserved failure allowance; expiry is never extended. */
    fun verificationSucceeded(token: String, account: java.util.UUID) {
        redis.execute(
            RELEASE,
            listOf(
                "scheduler:totp-limit:account:$account",
                "scheduler:totp-limit:grant:" + StaffLoginSelectionStore.hash(token),
            ),
        ) ?: error("Redis TOTP limiter unavailable")
    }

    fun consume(token: String) {
        if (redis.opsForValue().getAndDelete(key(token)) == null) expired()
    }

    /** Restore only a rejected code, retaining the original token, attempt budget and absolute deadline. */
    fun restore(token: String, grant: TotpGrant) {
        val ttl = Duration.between(clock.instant(), grant.expiresAt)
        if (ttl.isZero || ttl.isNegative) return
        val key = key(token)
        redis.opsForValue().setIfAbsent(key, cipher.encrypt(mapper.writeValueAsString(grant), key), ttl)
            ?: error("Redis TOTP grant unavailable")
    }

    private fun limit(scope: String, max: Long, seconds: Long) {
        val count =
            redis.execute(INCREMENT, listOf("scheduler:totp-limit:$scope"), seconds.toString())
                ?: error("Redis TOTP limiter unavailable")
        if (count > max) throw RateLimitExceededException(retryAfterSeconds = seconds)
    }

    private fun key(token: String) = "scheduler:totp-grant:" + StaffLoginSelectionStore.hash(token)

    companion object {
        private val RELEASE =
            RedisScript.of(
                "for _, k in ipairs(KEYS) do local v = tonumber(redis.call('GET', k) or '0'); if v > 0 then redis.call('DECR', k) end end; return 1",
                Long::class.java,
            )
        private val INCREMENT =
            RedisScript.of(
                "local n = redis.call('INCR', KEYS[1]); if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end; return n",
                Long::class.java,
            )

        fun expired(): Nothing =
            throw ApiException(
                ErrorCode.TOTP_CHALLENGE_EXPIRED,
                "Weryfikacja wygasła lub jest nieaktualna. Zaloguj się ponownie.",
            )
    }
}
