package pl.kacosmetology.scheduler.quote

import java.time.Instant
import java.time.LocalDateTime
import java.util.*
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.company.effectivePrice
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.offering.OfferingRepository

/** Resolves only commercial terms; booking must independently validate all current hard scheduling rules. */
@Component
class QuotePolicy(
    private val quotes: BookingQuoteRepository,
    private val offerings: OfferingRepository,
    private val companies: CompanyRepository,
) {
    /**
     * Resolves a quote against the authoritative [now] read by booking after its locks/replay lookup. A valid quote
     * preserves its agreement even when the service is inactive or current catalog terms are cheaper.
     */
    @Transactional(readOnly = true)
    fun resolve(
        customerId: UUID,
        serviceId: UUID,
        startTime: LocalDateTime,
        quoteId: UUID?,
        now: Instant,
    ): CommercialAgreement {
        val quote = quoteId?.let {
            quotes.findByIdAndCustomerId(it, customerId)
                ?: throw ApiException(ErrorCode.NOT_FOUND, "Nie znaleziono oferty cenowej")
        }
        val service =
            offerings.findById(serviceId).orElseThrow { ApiException(ErrorCode.NOT_FOUND, "Nie znaleziono usługi") }
        val start = quoteStartInstant(startTime)
        if (quote != null) {
            if (quote.serviceId != serviceId || quote.companyId != service.companyId || quote.startTime != start) {
                throw ApiException(ErrorCode.BAD_REQUEST, "Oferta cenowa nie dotyczy wybranej usługi lub terminu")
            }
            if (now.isBefore(quote.expiresAt)) return quote.agreement()
        }
        val company =
            companies.findById(service.companyId).orElseThrow {
                ApiException(ErrorCode.NOT_FOUND, "Nie znaleziono firmy")
            }
        val current =
            CommercialAgreement(
                service.name,
                company.effectivePrice(service.price, startTime, now),
                service.durationMinutes,
            )
        if (quote != null) {
            if (!service.active || current != quote.agreement()) {
                throw ApiException(
                    ErrorCode.QUOTE_RECONFIRMATION_REQUIRED,
                    "Oferta cenowa wygasła. Potwierdź aktualne warunki rezerwacji",
                )
            }
            return quote.agreement()
        }
        if (!service.active) throw ApiException(ErrorCode.OFFERING_UNAVAILABLE, "Usługa jest niedostępna")
        return current
    }
}

/** Immutable accepted service snapshot, copied into the reservation by the booking transaction. */
data class CommercialAgreement(val serviceName: String, val price: Int, val durationMinutes: Int)

/** Resolves only unique Warsaw wall times; quote APIs never silently choose a DST offset. */
internal fun quoteStartInstant(value: LocalDateTime): Instant {
    val offsets = BUSINESS_ZONE.rules.getValidOffsets(value)
    if (offsets.size != 1)
        throw ApiException(ErrorCode.INVALID_LOCAL_TIME, "Termin jest niejednoznaczny lub nie istnieje")
    return value.toInstant(offsets.single())
}

private fun BookingQuote.agreement() = CommercialAgreement(acceptedServiceName, price, durationMinutes)
