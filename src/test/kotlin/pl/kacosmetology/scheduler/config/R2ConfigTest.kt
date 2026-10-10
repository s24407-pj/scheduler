package pl.kacosmetology.scheduler.config

import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

/** Unit tests for credential handling in [R2Config]. */
class R2ConfigTest {

    @Test
    fun `client should start without optional R2 credentials`() {
        R2Config()
            .s3Client(
                R2Properties(endpoint = "https://example.invalid"),
            )
            .use {}
    }

    @Test
    fun `client should reject partially configured R2 credentials`() {
        assertFailsWith<IllegalArgumentException> {
            R2Config()
                .s3Client(
                    R2Properties(endpoint = "https://example.invalid", accessKey = "access-key"),
                )
        }
    }
}
