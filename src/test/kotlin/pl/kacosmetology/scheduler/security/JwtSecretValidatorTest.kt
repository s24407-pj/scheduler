package pl.kacosmetology.scheduler.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.env.MockEnvironment

class JwtSecretValidatorTest {

    private val strongSecret = "UnikalnySekretProdukcyjnyKtoryMaZdecydowaniePonad32Bajty"

    @Test
    fun `should reject development default secret without dev or test profile`() {
        assertThrows<IllegalArgumentException> {
            JwtSecretValidator(JwtSecretValidator.DEV_DEFAULT_SECRET, environment("prod"))
        }
        assertThrows<IllegalArgumentException> {
            JwtSecretValidator(JwtSecretValidator.DEV_DEFAULT_SECRET, environment())
        }
    }

    @Test
    fun `should accept development default secret in dev and test profiles`() {
        assertDoesNotThrow { JwtSecretValidator(JwtSecretValidator.DEV_DEFAULT_SECRET, environment("dev")) }
        assertDoesNotThrow { JwtSecretValidator(JwtSecretValidator.DEV_DEFAULT_SECRET, environment("test")) }
    }

    @Test
    fun `should reject secret shorter than 32 bytes in every profile`() {
        assertThrows<IllegalArgumentException> { JwtSecretValidator("za-krotki-sekret", environment("dev")) }
    }

    @Test
    fun `should accept unique strong secret in production`() {
        assertDoesNotThrow { JwtSecretValidator(strongSecret, environment("prod")) }
    }

    private fun environment(vararg profiles: String) = MockEnvironment().apply { setActiveProfiles(*profiles) }
}
