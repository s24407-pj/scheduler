package pl.kacosmetology.scheduler.auth

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.env.MockEnvironment

class TotpCryptoTest {
    private val secret = TotpCodes.base32("12345678901234567890".toByteArray())

    private fun codes(seconds: Long) = TotpCodes(Clock.fixed(Instant.ofEpochSecond(seconds), ZoneOffset.UTC))

    @Test
    fun `RFC SHA1 vectors include leading zeroes and long counters`() {
        mapOf(
                59L to "287082",
                1111111109L to "081804",
                1111111111L to "050471",
                1234567890L to "005924",
                2000000000L to "279037",
                20000000000L to "353130",
            )
            .forEach { (seconds, expected) -> assertEquals(expected, codes(seconds).at(secret, seconds / 30)) }
        assertEquals("12345678901234567890", String(TotpCodes.decodeBase32(secret)))
    }

    @Test
    fun `window admits only adjacent unused counters and validates exact ASCII digits`() {
        val codes = codes(3000)
        for (step in 98L..102L) {
            val match = codes.match(secret, codes.at(secret, step))
            if (step in 99L..101L) assertEquals(step, match) else assertNull(match)
        }
        assertNull(codes.match(secret, codes.at(secret, 100), 100))
        assertNull(codes.match(secret, codes.at(secret, 99), 100))
        assertEquals(101L, codes.match(secret, codes.at(secret, 101), 100))
        for (invalid in listOf("", "12345", "1234567", "abcdef", "１２３４５６", " 123456")) assertNull(
            codes.match(secret, invalid),
        )
        assertEquals(99L, codes(2999).match(secret, codes.at(secret, 99)))
        assertEquals(100L, codes(3000).match(secret, codes.at(secret, 100)))
    }

    @Test
    fun `secrets and recovery codes are independent random values`() {
        val codes = codes(3000)
        val secrets = List(50) { codes.newSecret() }
        assertEquals(50, secrets.toSet().size)
        secrets.forEach { assertEquals(20, TotpCodes.decodeBase32(it).size) }
        val recovery = codes.recoveryCodes()
        assertEquals(10, recovery.toSet().size)
        recovery.forEach { assertEquals(16, TotpCodes.decodeBase32(it.replace("-", "")).size) }
    }

    @Test
    fun `GCM requires correct key binding intact ciphertext and a private production key`() {
        val cipher = TotpCipher("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", MockEnvironment())
        val encrypted = cipher.encrypt(secret, "account-a")
        assertEquals(secret, cipher.decrypt(encrypted, "account-a"))
        assertNotEquals(encrypted, cipher.encrypt(secret, "account-a"))
        assertFalse(encrypted.contains(secret))
        assertThrows<Exception> { cipher.decrypt(encrypted, "account-b") }
        assertThrows<Exception> {
            TotpCipher("AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=", MockEnvironment())
                .decrypt(encrypted, "account-a")
        }
        assertThrows<Exception> { cipher.decrypt(encrypted.dropLast(5) + "AAAAA", "account-a") }
        assertThrows<IllegalArgumentException> { TotpCipher("", MockEnvironment()) }
        assertThrows<IllegalArgumentException> {
            TotpCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", MockEnvironment())
        }
        TotpCipher(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            MockEnvironment().also { it.setActiveProfiles("dev") },
        )
    }
}
