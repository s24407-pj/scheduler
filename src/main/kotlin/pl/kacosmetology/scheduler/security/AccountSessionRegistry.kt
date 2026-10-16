package pl.kacosmetology.scheduler.security

import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.stereotype.Component

/** Account generations make revocation authoritative even when a concurrent request saves an older session later. */
@Component
class AccountSessionRegistry(
    private val redis: StringRedisTemplate,
    private val sessions: FindByIndexNameSessionRepository<out Session>,
    private val clock: Clock,
) {
    /** Attributes saved together with the Spring Security context; customer deadlines never change with activity. */
    fun sessionAttributes(principal: CustomUserDetails): Map<String, Any> {
        val accountKey = principal.accountKey()
        val generation = currentGeneration(accountKey)
        return buildMap {
            put(GENERATION_ATTRIBUTE, generation)
            put(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, indexedPrincipal(accountKey, generation))
            if (principal.role == "ROLE_CUSTOMER")
                put(CUSTOMER_DEADLINE_ATTRIBUTE, clock.instant().plus(SESSION_DURATION))
        }
    }

    /** Checks the revocation generation and exact customer absolute deadline before every authorization. */
    fun isAuthorized(principal: CustomUserDetails, attribute: (String) -> Any?): Boolean {
        val generation = attribute(GENERATION_ATTRIBUTE) as? Long ?: return false
        val accountKey = principal.accountKey()
        if (generation != currentGeneration(accountKey)) return false
        if (
            attribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME) !=
                indexedPrincipal(accountKey, generation)
        ) {
            return false
        }
        if (principal.role == "ROLE_CUSTOMER") {
            val deadline = attribute(CUSTOMER_DEADLINE_ATTRIBUTE) as? Instant ?: return false
            if (!clock.instant().isBefore(deadline)) return false
        }
        return true
    }

    /**
     * Revokes only this realm/account. INCR is the linearization point; delayed saves in older generations cannot
     * authorize, while a login in the new generation remains indexed separately. Generation keys intentionally never
     * expire: resetting to zero while old sessions exist could make a revoked session valid again.
     */
    fun revokeAccount(accountKey: String) {
        val generation = requireNotNull(redis.opsForValue().increment(generationKey(accountKey)))
        sessions.findByPrincipalName(indexedPrincipal(accountKey, generation - 1)).keys.forEach(sessions::deleteById)
    }

    private fun currentGeneration(accountKey: String): Long =
        redis.opsForValue().get(generationKey(accountKey))?.toLong() ?: 0L

    private fun generationKey(accountKey: String): String = "scheduler:account-session-generation:$accountKey"

    private fun indexedPrincipal(accountKey: String, generation: Long): String = "$accountKey:$generation"

    companion object {
        /** Absolute customer lifetime and staff inactivity interval. */
        val SESSION_DURATION: Duration = Duration.ofHours(24)
        /** Generation captured when authentication succeeds. */
        const val GENERATION_ATTRIBUTE = "scheduler.accountGeneration"
        /** Immutable customer login deadline, measured in elapsed time. */
        const val CUSTOMER_DEADLINE_ATTRIBUTE = "scheduler.customerDeadline"
    }
}
