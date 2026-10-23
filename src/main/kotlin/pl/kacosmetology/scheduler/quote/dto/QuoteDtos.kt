package pl.kacosmetology.scheduler.quote.dto

import java.time.Instant
import java.time.LocalDateTime
import java.util.*

/** Customer-requested commercial preview; the company is determined from the public service identifier. */
data class CreateQuoteRequest(
    val serviceId: UUID,
    val startTime: LocalDateTime,
)

/** Server-owned quote identifier and accepted commercial terms; expiry is an absolute instant. */
data class QuoteResponse(
    val quoteId: UUID,
    val companyId: UUID,
    val serviceId: UUID,
    val serviceName: String,
    val price: Int,
    val durationMinutes: Int,
    val startTime: LocalDateTime,
    val expiresAt: Instant,
)
