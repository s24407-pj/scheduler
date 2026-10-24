package pl.kacosmetology.scheduler.quote

import java.time.Clock
import java.time.Duration
import java.util.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.EntityIds
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.quote.dto.CreateQuoteRequest
import pl.kacosmetology.scheduler.quote.dto.QuoteResponse

/** Creates durable customer quotes without holding a slot or establishing staff visibility. */
@Service
class QuoteService(
    private val quotes: BookingQuoteRepository,
    private val offerings: OfferingRepository,
    private val policy: QuotePolicy,
    private val clock: Clock,
) {
    /** Saves the current commercial snapshot for ten elapsed minutes from quote creation. */
    @Transactional
    fun create(customerId: UUID, request: CreateQuoteRequest, companyId: UUID): QuoteResponse {
        offerings.findByIdAndCompanyId(request.serviceId, companyId)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "Nie znaleziono usługi")
        val now = clock.instant()
        val start = quoteStartInstant(request.startTime)
        if (!start.isAfter(now)) throw ApiException(ErrorCode.BAD_REQUEST, "Termin musi być w przyszłości")
        val agreement = policy.resolve(customerId, request.serviceId, request.startTime, null, now)
        val service =
            offerings.findById(request.serviceId).orElseThrow {
                ApiException(ErrorCode.NOT_FOUND, "Nie znaleziono usługi")
            }
        val quote =
            quotes.save(
                BookingQuote(
                    EntityIds.next(),
                    customerId,
                    service.companyId,
                    request.serviceId,
                    start,
                    agreement.serviceName,
                    agreement.price,
                    agreement.durationMinutes,
                    now.plus(Duration.ofMinutes(10)),
                    now,
                ),
            )
        return QuoteResponse(
            quote.id,
            quote.companyId,
            quote.serviceId,
            quote.acceptedServiceName,
            quote.price,
            quote.durationMinutes,
            request.startTime,
            quote.expiresAt,
        )
    }
}
