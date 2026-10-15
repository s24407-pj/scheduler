package pl.kacosmetology.scheduler.reservation

import java.security.MessageDigest
import org.springframework.dao.CannotAcquireLockException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import tools.jackson.databind.ObjectMapper

/** Durable account-scoped submission identity, bound even when booking validation fails. */
@Service
class BookingSubmissionService(
    transactionManager: PlatformTransactionManager,
    private val jdbc: JdbcTemplate,
    private val reservations: ReservationRepository,
    private val mapper: ObjectMapper,
) {
    private val independent =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    /** Serializes identical submissions for at most five seconds and replays the current reservation forever. */
    fun execute(accountKey: String, key: String, request: Any, create: () -> BookingResult): BookingResult {
        if (key.isBlank() || key.length > 200) {
            throw ApiException(ErrorCode.BAD_REQUEST, "Idempotency-Key musi zawierać od 1 do 200 znaków")
        }
        val payload = mapper.writeValueAsString(request)
        val hash =
            MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        try {
            independent.executeWithoutResult {
                jdbc.execute("SET LOCAL lock_timeout = '5s'")
                jdbc.update(
                    "INSERT INTO booking_submissions(account_key, submission_key, request_hash, request_payload) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                    accountKey,
                    key,
                    hash,
                    payload,
                )
            }
            return checkNotNull(
                independent.execute {
                    jdbc.execute("SET LOCAL lock_timeout = '5s'")
                    val submission =
                        jdbc.queryForMap(
                            "SELECT request_hash, reservation_id FROM booking_submissions WHERE account_key = ? AND submission_key = ? FOR UPDATE",
                            accountKey,
                            key,
                        )
                    if (submission["request_hash"] != hash) {
                        throw ApiException(
                            ErrorCode.IDEMPOTENCY_CONFLICT,
                            "Ten klucz został już powiązany z innym żądaniem",
                        )
                    }
                    val reservationId = (submission["reservation_id"] as? Number)?.toLong()
                    if (reservationId != null) {
                        BookingResult(reservations.findById(reservationId).orElseThrow(), created = false)
                    } else {
                        val result = create()
                        reservations.flush()
                        jdbc.update(
                            "UPDATE booking_submissions SET reservation_id = ? WHERE account_key = ? AND submission_key = ?",
                            requireNotNull(result.reservation.id),
                            accountKey,
                            key,
                        )
                        result
                    }
                },
            )
        } catch (_: CannotAcquireLockException) {
            throw ApiException(
                ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                "Poprzednie żądanie nadal jest przetwarzane; ponów próbę",
            )
        }
    }
}
