package pl.kacosmetology.scheduler.security

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

class SessionStoreTest {

    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
    private val setOps = mockk<SetOperations<String, String>>(relaxed = true)
    private val redisTemplate =
        mockk<StringRedisTemplate>(relaxed = true) {
            every { opsForValue() } returns valueOps
            every { opsForSet() } returns setOps
        }
    private val sessionStore = SessionStore(redisTemplate)

    @Test
    fun `create should store a random session for the user with TTL and index it`() {
        val ttl = Duration.ofHours(24)
        val storedKeys = mutableListOf<String>()

        val first = sessionStore.create(7L, ttl)
        val second = sessionStore.create(7L, ttl)

        assertNotEquals(first, second)
        assertTrue(first.length >= 43, "Session ID should encode 256 random bits")
        verify { valueOps.set(capture(storedKeys), "7", ttl) }
        assertEquals(listOf("session:$first", "session:$second"), storedKeys)
        verify { setOps.add("user-sessions:7", first) }
        verify { redisTemplate.expire("user-sessions:7", ttl) }
    }

    @Test
    fun `isActive should require the session to belong to the given user`() {
        every { valueOps.get("session:abc") } returns "7"

        assertTrue(sessionStore.isActive("abc", 7L))
        assertFalse(sessionStore.isActive("abc", 8L))
    }

    @Test
    fun `isActive should reject missing session`() {
        every { valueOps.get("session:gone") } returns null

        assertFalse(sessionStore.isActive("gone", 7L))
    }

    @Test
    fun `revoke should delete the session and remove it from the user index`() {
        sessionStore.revoke("abc", 7L)

        verify { redisTemplate.delete("session:abc") }
        verify { setOps.remove("user-sessions:7", "abc") }
    }

    @Test
    fun `revokeAll should delete every indexed session and the index`() {
        every { setOps.members("user-sessions:7") } returns setOf("a", "b")

        sessionStore.revokeAll(7L)

        verify { redisTemplate.delete(listOf("session:a", "session:b", "user-sessions:7")) }
    }
}
