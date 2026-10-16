package pl.kacosmetology.scheduler.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CorsConfigTest {
    @Test
    fun `credentialed origins reject wildcard and empty configuration`() {
        for (origins in listOf("*", "http://localhost:5173,*", "")) {
            assertThrows<IllegalArgumentException> { CorsConfig(origins) }
        }
    }
}
