package pl.kacosmetology.scheduler.quote

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository

class QuotePolicyTest {
    private val quotes = mockk<BookingQuoteRepository>()
    private val offerings = mockk<OfferingRepository>()
    private val companies = mockk<CompanyRepository>()
    private val policy = QuotePolicy(quotes, offerings, companies)
    private val id = UUID.randomUUID()
    private val now = Instant.parse("2026-06-01T08:00:00Z")
    private val start = LocalDateTime.parse("2026-06-01T14:00:00")
    private val company = Company(id = 1, name = "Salon", lastMinuteDiscountPercent = 15)
    private lateinit var service: Offering
    private lateinit var quote: BookingQuote

    @BeforeEach
    fun setup() {
        service = Offering(id = 2, companyId = 1, name = "Original", durationMinutes = 30, price = 101)
        quote = BookingQuote(id, 3, 1, 2, quoteStartInstant(start), "Original", 86, 30, now.plusSeconds(600), now)
        every { offerings.findById(2) } returns Optional.of(service)
        every { companies.findById(1) } returns Optional.of(company)
        every { quotes.findByIdAndCustomerId(id, 3) } answers { quote }
    }

    @Test
    fun `without quote current active service gets effective customer price`() {
        assertEquals(CommercialAgreement("Original", 86, 30), policy.resolve(3, 2, start, null, now))
        verify(exactly = 0) { quotes.findByIdAndCustomerId(any(), any()) }
        service.active = false
        assertEquals(
            ErrorCode.OFFERING_UNAVAILABLE,
            assertThrows<ApiException> { policy.resolve(3, 2, start, null, now) }.code,
        )
    }

    @Test
    fun `valid quote protects name duration price even when inactive or current terms cheaper`() {
        service.active = false
        service.name = "Renamed"
        service.durationMinutes = 90
        service.price = 1
        assertEquals(CommercialAgreement("Original", 86, 30), policy.resolve(3, 2, start, id, now.plusSeconds(599)))
        verify(exactly = 0) { companies.findById(any()) }
    }

    @Test
    fun `validity is exactly ten elapsed minutes including across Warsaw DST change`() {
        val beforeTransition = Instant.parse("2026-03-29T00:55:00Z")
        val laterStart = LocalDateTime.parse("2026-03-29T04:00:00")
        quote =
            BookingQuote(
                id,
                3,
                1,
                2,
                quoteStartInstant(laterStart),
                "Original",
                86,
                30,
                beforeTransition.plus(Duration.ofMinutes(10)),
                beforeTransition,
            )
        service.active = false
        assertEquals(86, policy.resolve(3, 2, laterStart, id, beforeTransition.plusSeconds(599)).price)
        assertEquals(
            ErrorCode.QUOTE_RECONFIRMATION_REQUIRED,
            assertThrows<ApiException> { policy.resolve(3, 2, laterStart, id, beforeTransition.plusSeconds(600)) }.code,
        )
    }

    @Test
    fun `expired quote proceeds only if active service effective terms match all accepted values`() {
        assertEquals(CommercialAgreement("Original", 86, 30), policy.resolve(3, 2, start, id, now.plusSeconds(600)))
        listOf<(Offering) -> Unit>(
                { it.name = "Renamed" },
                { it.durationMinutes = 31 },
                { it.price = 100 },
                { it.active = false },
            )
            .forEach { change ->
                setup()
                change(service)
                val failure = assertThrows<ApiException> { policy.resolve(3, 2, start, id, now.plusSeconds(600)) }
                assertEquals(ErrorCode.QUOTE_RECONFIRMATION_REQUIRED, failure.code)
            }
    }

    @Test
    fun `expired quote compares effective price even when catalog base price changed`() {
        service.price = 102 // 86.70 -> 87: changed effective agreement
        assertEquals(
            ErrorCode.QUOTE_RECONFIRMATION_REQUIRED,
            assertThrows<ApiException> { policy.resolve(3, 2, start, id, now.plusSeconds(600)) }.code,
        )
        service.price = 101
        company.lastMinuteDiscountPercent = 0
        assertEquals(
            ErrorCode.QUOTE_RECONFIRMATION_REQUIRED,
            assertThrows<ApiException> { policy.resolve(3, 2, start, id, now.plusSeconds(600)) }.code,
        )
    }

    @Test
    fun `foreign or absent quote is opaque missing before any service disclosure`() {
        every { quotes.findByIdAndCustomerId(id, 99) } returns null
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ApiException> { policy.resolve(99, 2, start, id, now) }.code)
        verify(exactly = 0) { offerings.findById(any()) }
    }

    @Test
    fun `owned quote cannot be tampered onto another service company or start`() {
        assertEquals(
            ErrorCode.BAD_REQUEST,
            assertThrows<ApiException> { policy.resolve(3, 2, start.plusMinutes(1), id, now) }.code,
        )
        every { offerings.findById(4) } returns
            Optional.of(Offering(id = 4, companyId = 9, name = "Foreign", durationMinutes = 30, price = 100))
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows<ApiException> { policy.resolve(3, 4, start, id, now) }.code)
    }

    @Test
    fun `quote start rejects Warsaw DST gap and overlap`() {
        listOf(LocalDateTime.parse("2026-03-29T02:30:00"), LocalDateTime.parse("2026-10-25T02:30:00")).forEach {
            assertEquals(
                ErrorCode.INVALID_LOCAL_TIME,
                assertThrows<ApiException> { policy.resolve(3, 2, it, null, now) }.code,
            )
        }
    }
}
