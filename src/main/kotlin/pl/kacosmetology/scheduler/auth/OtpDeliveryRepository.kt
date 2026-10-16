package pl.kacosmetology.scheduler.auth

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/** Durable phone-only OTP delivery metadata. Plaintext exists only while a still-live code needs delivery. */
@Repository
class OtpDeliveryRepository(private val jdbc: JdbcTemplate) {
    /** Serializes replacement intent for one canonical phone without creating an identity or profile. */
    fun lockPhone(phone: String) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,82341))", String::class.java, phone)
    }

    /** Replacing a still-current code clears older pending plaintext while retaining terminal metadata. */
    fun discardOtherPending(phone: String, currentCode: String, now: Instant) {
        jdbc.update(
            "UPDATE otp_deliveries SET state='DISCARDED',code=NULL,updated_at=? WHERE phone_number=? AND code<>? AND state IN ('PENDING','TEMPORARY_FAILURE')",
            Timestamp.from(now),
            phone,
            currentCode,
        )
    }

    /** Discards only the exact consumed code, so a delayed consume event never invalidates its replacement. */
    fun discardConsumed(phone: String, code: String, now: Instant) {
        jdbc.update(
            "UPDATE otp_deliveries SET state='DISCARDED',code=NULL,updated_at=? WHERE phone_number=? AND code=? AND state IN ('PENDING','TEMPORARY_FAILURE')",
            Timestamp.from(now),
            phone,
            code,
        )
    }

    /** Creates intent in the issuing transaction; an already-invalid code retains metadata without plaintext. */
    fun insert(id: UUID, phone: String, code: String?, now: Instant, expiry: Instant) {
        jdbc.update(
            """
            INSERT INTO otp_deliveries(id,phone_number,code,state,attempts,created_at,expires_at,next_attempt_at,updated_at)
            VALUES (?,?,?, ?,0,?,?,?,?) ON CONFLICT(id) DO NOTHING
            """
                .trimIndent(),
            id,
            phone,
            code,
            if (code == null) "DISCARDED" else "PENDING",
            Timestamp.from(now),
            Timestamp.from(expiry),
            Timestamp.from(now),
            Timestamp.from(now),
        )
    }

    /** Enumerates due work; claiming is separately protected by the row lock and current-state check. */
    fun due(now: Instant): List<UUID> =
        jdbc
            .queryForList(
                "SELECT id FROM otp_deliveries WHERE state IN ('PENDING','TEMPORARY_FAILURE') AND next_attempt_at<=? ORDER BY created_at,id LIMIT 100",
                UUID::class.java,
                Timestamp.from(now),
            )
            .filterNotNull()

    /** Locks and reads one work item without generating secret-bearing data-class diagnostics. */
    fun lock(id: UUID): OtpDelivery? =
        jdbc
            .query(
                "SELECT * FROM otp_deliveries WHERE id=? FOR UPDATE",
                { rs, _ ->
                    OtpDelivery(
                        rs.getObject("id", UUID::class.java),
                        rs.getString("phone_number"),
                        rs.getString("code"),
                        rs.getString("state"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getInt("attempts"),
                    )
                },
                id,
            )
            .singleOrNull()

    /** Commits the attempt marker before any external provider call. */
    fun sending(id: UUID, now: Instant) {
        jdbc.update(
            "UPDATE otp_deliveries SET state='SENDING',attempts=attempts+1,updated_at=? WHERE id=?",
            Timestamp.from(now),
            id,
        )
    }

    /** Saves the classified outcome and removes plaintext on every terminal result. */
    fun result(id: UUID, state: String, now: Instant, next: Instant = now) {
        jdbc.update(
            "UPDATE otp_deliveries SET state=?,code=CASE WHEN ?='TEMPORARY_FAILURE' THEN code ELSE NULL END,updated_at=?,next_attempt_at=? WHERE id=?",
            state,
            state,
            Timestamp.from(now),
            Timestamp.from(next),
            id,
        )
    }

    /** Unknown in-flight sends become ambiguous; old secrets are scrubbed even when their retry time is later. */
    fun recoverAndExpire(now: Instant, restart: Boolean) {
        jdbc.update(
            "UPDATE otp_deliveries SET state='AMBIGUOUS',code=NULL,updated_at=? WHERE state='SENDING' AND (? OR expires_at<=?)",
            Timestamp.from(now),
            restart,
            Timestamp.from(now),
        )
        jdbc.update(
            "UPDATE otp_deliveries SET state='DISCARDED',code=NULL,updated_at=? WHERE state IN ('PENDING','TEMPORARY_FAILURE') AND expires_at<=?",
            Timestamp.from(now),
            Timestamp.from(now),
        )
        jdbc.update(
            "UPDATE otp_deliveries SET code=NULL WHERE state IN ('ACCEPTED','AMBIGUOUS','DISCARDED') AND code IS NOT NULL",
        )
    }
}

/** Secret-bearing internal work item, deliberately without a generated toString/equals representation. */
class OtpDelivery(
    val id: UUID,
    val phone: String,
    val code: String?,
    val state: String,
    val expiresAt: Instant,
    val attempts: Int,
)
