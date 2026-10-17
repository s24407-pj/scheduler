package pl.kacosmetology.scheduler.quote

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.quote.dto.CreateQuoteRequest
import pl.kacosmetology.scheduler.testId

class QuoteServiceTest {
    private val repository = mockk<BookingQuoteRepository>()
    private val offerings = mockk<OfferingRepository>()
    private val policy = mockk<QuotePolicy>()
    private val now = Instant.parse("2026-06-01T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val service = QuoteService(repository, offerings, policy, clock)

    @Test
    fun `creates durable snapshot with UUID ten elapsed minute lifetime using UTC JVM clock`() {
        val start = LocalDateTime.parse("2026-06-01T10:01:00")
        every { policy.resolve(testId(3), testId(2), start, null, now) } returns CommercialAgreement("Accepted", 86, 30)
        every { offerings.findById(testId(2)) } returns
            Optional.of(
                Offering(id = testId(2), companyId = testId(1), name = "Changed", durationMinutes = 60, price = 200),
            )
        val saved = slot<BookingQuote>()
        every { repository.save(capture(saved)) } answers { firstArg() }
        val result = service.create(testId(3), CreateQuoteRequest(testId(2), start))
        assertEquals("Accepted", result.serviceName)
        assertEquals(86, result.price)
        assertEquals(30, result.durationMinutes)
        assertEquals(now.plusSeconds(600), result.expiresAt)
        assertEquals(Instant.parse("2026-06-01T08:01:00Z"), saved.captured.startTime)
        assertEquals(testId(3), saved.captured.customerId)
        assertEquals(now, saved.captured.createdAt)
        assertEquals(saved.captured.id, result.quoteId)
    }

    @Test
    fun `past or current start never creates quote`() {
        listOf("2026-06-01T09:59:59", "2026-06-01T10:00:00").forEach {
            assertThrows<ApiException> {
                service.create(testId(3), CreateQuoteRequest(testId(2), LocalDateTime.parse(it)))
            }
        }
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { policy.resolve(any(), any(), any(), any(), any()) }
    }
}
