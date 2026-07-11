package pl.kacosmetology.scheduler.config

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/** Unit tests for credential handling in [R2Config]. */
class R2ConfigTest {

    @Test
    fun `client should start without optional R2 credentials`() {
        R2Config().s3Client(
            R2Properties(endpoint = "https://example.invalid")
        ).use { }
    }

    @Test
    fun `client should reject partially configured R2 credentials`() {
        assertFailsWith<IllegalArgumentException> {
            R2Config().s3Client(
                R2Properties(endpoint = "https://example.invalid", accessKey = "access-key")
            )
        }
    }
}
