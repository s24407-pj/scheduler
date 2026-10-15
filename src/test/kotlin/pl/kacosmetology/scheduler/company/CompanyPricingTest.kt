package pl.kacosmetology.scheduler.company

import java.time.Instant
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompanyPricingTest {
    private val now = Instant.parse("2026-06-01T08:00:00Z")
    private val start = LocalDateTime.parse("2026-06-01T12:00:00")

    @Test
    fun `discount rounds nearest with ties to even and cannot overflow Int intermediate`() {
        val company = Company(name = "Salon", lastMinuteDiscountPercent = 50)
        assertEquals(50, company.effectivePrice(101, start, now))
        assertEquals(52, company.effectivePrice(103, start, now))
        assertEquals(1_073_741_824, company.effectivePrice(Int.MAX_VALUE, start, now))
        company.lastMinuteDiscountPercent = 15
        assertEquals(84, company.effectivePrice(99, start, now))
        assertEquals(86, company.effectivePrice(101, start, now))
        company.lastMinuteDiscountPercent = 100
        assertEquals(0, company.effectivePrice(101, start, now))
    }

    @Test
    fun `both elapsed discount boundaries are inclusive`() {
        val company = Company(name = "Salon", lastMinuteDiscountPercent = 50, lastMinuteDiscountHours = 2)
        assertEquals(50, company.effectivePrice(100, LocalDateTime.parse("2026-06-01T10:00:00"), now))
        assertEquals(50, company.effectivePrice(100, start, now))
        assertEquals(100, company.effectivePrice(100, start.plusNanos(1), now))
        assertEquals(100, company.effectivePrice(100, LocalDateTime.parse("2026-06-01T09:59:59"), now))
    }

    @Test
    fun `discount windows measure elapsed hours through spring and autumn DST transitions`() {
        val company = Company(name = "Salon", lastMinuteDiscountPercent = 50, lastMinuteDiscountHours = 2)
        assertEquals(
            50,
            company.effectivePrice(
                100,
                LocalDateTime.parse("2026-03-29T04:30:00"),
                Instant.parse("2026-03-29T00:30:00Z"),
            ),
        )
        assertEquals(
            100,
            company.effectivePrice(
                100,
                LocalDateTime.parse("2026-10-25T03:30:00"),
                Instant.parse("2026-10-24T23:30:00Z"),
            ),
        )
        // Both instants represent the repeated local 02:30, with distinct offsets. Pricing must keep the distinction.
        assertEquals(
            100,
            company.effectivePrice(
                100,
                LocalDateTime.parse("2026-10-25T04:30:00"),
                Instant.parse("2026-10-25T00:30:00Z"),
            ),
        )
        assertEquals(
            50,
            company.effectivePrice(
                100,
                LocalDateTime.parse("2026-10-25T04:30:00"),
                Instant.parse("2026-10-25T01:30:00Z"),
            ),
        )
    }
}
