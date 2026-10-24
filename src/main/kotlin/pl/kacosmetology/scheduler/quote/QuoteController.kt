package pl.kacosmetology.scheduler.quote

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import pl.kacosmetology.scheduler.quote.dto.CreateQuoteRequest
import pl.kacosmetology.scheduler.quote.dto.QuoteResponse
import pl.kacosmetology.scheduler.security.CustomUserDetails

/** Customer-only commercial quotes; cookie authentication and CSRF apply to the write endpoint. */
@RestController
@RequestMapping("/api/quotes")
class QuoteController(private val service: QuoteService) {
    /** Creates a ten-minute quote without reserving employee capacity. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    fun create(
        @AuthenticationPrincipal customer: CustomUserDetails,
        @Valid @RequestBody request: CreateQuoteRequest,
    ): QuoteResponse = service.create(customer.id, request, customer.requireCompanyId())
}
