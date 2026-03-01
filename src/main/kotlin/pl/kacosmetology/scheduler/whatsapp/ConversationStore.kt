package pl.kacosmetology.scheduler.whatsapp

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * Redis-backed store for [ConversationState] objects, keyed by normalised phone number.
 * Each entry expires after 30 minutes of inactivity.
 */
@Component
class ConversationStore(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {

    companion object {
        private const val KEY_PREFIX = "whatsapp:conv:"
        private val TTL = Duration.ofMinutes(30)
    }

    /** Returns the current conversation state for [phone], or a fresh [ConversationState] if none exists. */
    fun get(phone: String): ConversationState {
        val json = redisTemplate.opsForValue().get("$KEY_PREFIX$phone")
            ?: return ConversationState()
        return objectMapper.readValue(json, ConversationState::class.java)
    }

    /** Persists [state] for [phone] and resets the 30-minute TTL. */
    fun save(phone: String, state: ConversationState) {
        val json = objectMapper.writeValueAsString(state)
        redisTemplate.opsForValue().set("$KEY_PREFIX$phone", json, TTL)
    }

    /** Removes the conversation state for [phone] (e.g. after cancellation or successful booking). */
    fun delete(phone: String) {
        redisTemplate.delete("$KEY_PREFIX$phone")
    }
}
