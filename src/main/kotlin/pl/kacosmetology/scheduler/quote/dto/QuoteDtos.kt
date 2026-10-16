package pl.kacosmetology.scheduler.quote.dto

import jakarta.validation.constraints.Positive
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/** Customer-requested commercial preview; the company is determined from the public service identifier. */
data class CreateQuoteRequest(
    @field:Positive(message = "Identyfikator usługi musi być dodatni") val serviceId: Long,
    val startTime: LocalDateTime,
)

/** Server-owned quote identifier and accepted commercial terms; expiry is an absolute instant. */
data class QuoteResponse(
    val quoteId: UUID,
    val companyId: Long,
    val serviceId: Long,
    val serviceName: String,
    val price: Int,
    val durationMinutes: Int,
    val startTime: LocalDateTime,
    val expiresAt: Instant,
)
