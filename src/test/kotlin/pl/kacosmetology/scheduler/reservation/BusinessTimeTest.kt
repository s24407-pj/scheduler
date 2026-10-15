package pl.kacosmetology.scheduler.reservation

import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.validateBusinessInterval

class BusinessTimeTest {
    @Test
    fun `gap overlap and transition spanning appointments are rejected`() {
        listOf(
                "2026-03-29T02:10" to "2026-03-29T03:10",
                "2026-10-25T02:10" to "2026-10-25T02:40",
                "2026-03-29T01:30" to "2026-03-29T03:30",
                "2026-10-25T01:30" to "2026-10-25T03:30",
            )
            .forEach { (start, end) ->
                assertThrows(ApiException::class.java) {
                    validateBusinessInterval(LocalDateTime.parse(start), LocalDateTime.parse(end))
                }
            }
        validateBusinessInterval(LocalDateTime.parse("2026-10-25T04:00"), LocalDateTime.parse("2026-10-25T05:00"))
    }
}
