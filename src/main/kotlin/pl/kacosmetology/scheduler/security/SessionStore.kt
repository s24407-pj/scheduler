package pl.kacosmetology.scheduler.security

import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * Redis-backed registry of issued tokens. Each token carries a session ID (`jti`) that must exist under `session:<id>`
 * for the token to be accepted, so deleting the key revokes the token before it expires. `user-sessions:<userId>`
 * indexes a user's session IDs for revoking all of them at once.
 */
@Component
class SessionStore(private val redisTemplate: StringRedisTemplate) {

    companion object {
        private const val SESSION_KEY_PREFIX = "session:"
        private const val USER_SESSIONS_KEY_PREFIX = "user-sessions:"
        private const val SESSION_ID_BYTES = 32
    }

    private val secureRandom = SecureRandom()

    /** Registers a new session for [userId] that expires after [ttl] and returns its random ID. */
    fun create(userId: Long, ttl: Duration): String {
        val bytes = ByteArray(SESSION_ID_BYTES).also { secureRandom.nextBytes(it) }
        val sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val userSessionsKey = "$USER_SESSIONS_KEY_PREFIX$userId"
        redisTemplate.opsForValue().set("$SESSION_KEY_PREFIX$sessionId", userId.toString(), ttl)
        redisTemplate.opsForSet().add(userSessionsKey, sessionId)
        redisTemplate.expire(userSessionsKey, ttl)
        return sessionId
    }

    /** Returns whether [sessionId] is still registered and belongs to [userId]. */
    fun isActive(sessionId: String, userId: Long): Boolean =
        redisTemplate.opsForValue().get("$SESSION_KEY_PREFIX$sessionId") == userId.toString()

    /** Revokes a single session of [userId]. */
    fun revoke(sessionId: String, userId: Long) {
        redisTemplate.delete("$SESSION_KEY_PREFIX$sessionId")
        redisTemplate.opsForSet().remove("$USER_SESSIONS_KEY_PREFIX$userId", sessionId)
    }

    /** Revokes every session of [userId]. */
    fun revokeAll(userId: Long) {
        val userSessionsKey = "$USER_SESSIONS_KEY_PREFIX$userId"
        val sessionIds = redisTemplate.opsForSet().members(userSessionsKey).orEmpty()
        redisTemplate.delete(sessionIds.map { "$SESSION_KEY_PREFIX$it" } + userSessionsKey)
    }
}
