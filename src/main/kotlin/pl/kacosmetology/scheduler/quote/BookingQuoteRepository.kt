package pl.kacosmetology.scheduler.quote

import java.util.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/** Account-scoped access to persisted commercial quotes. */
@Repository
interface BookingQuoteRepository : JpaRepository<BookingQuote, UUID> {
    /** Foreign and missing quote identifiers are indistinguishable. */
    fun findByIdAndCustomerId(id: UUID, customerId: UUID): BookingQuote?
}
