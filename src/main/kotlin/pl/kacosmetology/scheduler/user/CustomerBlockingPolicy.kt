package pl.kacosmetology.scheduler.user

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.reservation.CustomerBlockedBookingsEvent
import pl.kacosmetology.scheduler.reservation.ReservationAuditContext
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.reservation.ReservationStatus

/** Company-local blocks preserve penalty history and never affect authentication or other salons. */
@Component
class CustomerBlockingPolicy(
    private val companies: CompanyRepository,
    private val profiles: CompanyCustomerRepository,
    private val reservations: ReservationRepository,
    private val jdbc: JdbcTemplate,
    private val audit: ReservationAuditContext,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /** Manual blocking cancels future bookings atomically and preserves ongoing visits. */
    @Transactional
    fun block(companyId: UUID, customerId: UUID) {
        lockAndValidate(companyId, customerId)
        jdbc.update(
            "INSERT INTO company_customer_blocks(company_id,customer_id,blocked,manual_blocked) VALUES(?,?,true,true) ON CONFLICT(company_id,customer_id) DO UPDATE SET blocked=true,manual_blocked=true",
            companyId,
            customerId,
        )
        cancelFuture(companyId, customerId)
    }

    /** Owner reprieve clears both block sources without erasing no-show history or restoring visits. */
    @Transactional
    fun unblock(companyId: UUID, customerId: UUID) {
        lockAndValidate(companyId, customerId)
        jdbc.update(
            "UPDATE company_customer_blocks SET blocked=false,manual_blocked=false,automatic_blocked=false WHERE company_id=? AND customer_id=?",
            companyId,
            customerId,
        )
    }

    /** Current counts age out by Warsaw calendar date; aging alone never clears a persisted block. */
    fun currentCount(companyId: UUID, customerId: UUID): Int {
        val today = LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE)
        return jdbc.queryForObject<Int>(
            "SELECT count(*) FROM no_show_penalties WHERE company_id=? AND customer_id=? AND active AND appointment_date BETWEEN ? AND ?",
            companyId,
            customerId,
            today.minusYears(1),
            today,
        )!!
    }

    /** Caller holds the company lock; every cancellation passes through audit and optimistic versioning. */
    fun cancelFuture(companyId: UUID, customerId: UUID) {
        val now = LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE)
        val future =
            reservations
                .findAllByCompanyIdAndCustomerIdAndArchivedFalseOrderByStartTimeDesc(companyId, customerId)
                .filter {
                    it.companyId == companyId && it.status == ReservationStatus.BOOKED && it.startTime.isAfter(now)
                }
        future.forEach {
            audit.capture()
            it.status = ReservationStatus.CANCELLED
            it.internalCancellationReason = "Blokada klienta w salonie"
            reservations.saveAndFlush(it)
        }
        if (future.isNotEmpty())
            events.publishEvent(
                CustomerBlockedBookingsEvent(companyId, customerId, future.map { requireNotNull(it.id) }),
            )
    }

    private fun lockAndValidate(companyId: UUID, customerId: UUID) {
        if (
            companies.findByIdForUpdate(companyId) == null ||
                profiles.findByCompanyIdAndIdAndFirstBookingAtIsNotNull(companyId, customerId) == null
        ) {
            throw ApiException(ErrorCode.CUSTOMER_NOT_FOUND, "Klient nie istnieje w tym salonie")
        }
    }
}
