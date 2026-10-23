package pl.kacosmetology.scheduler.auth

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import pl.kacosmetology.scheduler.auth.sms.SmsSendingStats
import pl.kacosmetology.scheduler.auth.sms.SmsWork

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

    /** Selects one due item per free phone lane, without loading any OTP or raw phone into the executor queue. */
    fun due(now: Instant, excluded: Set<String>, limit: Int): List<SmsWork> {
        if (limit == 0) return emptyList()
        val exclusion =
            if (excluded.isEmpty()) "" else "AND md5(d.phone_number) NOT IN (${excluded.joinToString { "?" }})"
        return jdbc.query(
            """
            SELECT * FROM (
                SELECT DISTINCT ON (d.phone_number) d.id,md5(d.phone_number) AS lane,d.created_at
                FROM otp_deliveries d
                WHERE d.state IN ('PENDING','TEMPORARY_FAILURE') AND d.next_attempt_at<=?
                AND NOT EXISTS (SELECT 1 FROM otp_deliveries active
                    WHERE active.phone_number=d.phone_number AND active.state='SENDING')
                $exclusion
                ORDER BY d.phone_number,d.created_at,d.id
            ) candidates ORDER BY created_at,id LIMIT ?
            """
                .trimIndent(),
            { rs, _ ->
                SmsWork(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("lane"),
                    rs.getTimestamp("created_at").toInstant(),
                )
            },
            *(listOf<Any>(Timestamp.from(now)) + excluded + limit).toTypedArray(),
        )
    }

    /** Last observed durable backlog, including pending retries whose next-attempt time is still future. */
    fun pendingCount(): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM otp_deliveries WHERE state IN ('PENDING','TEMPORARY_FAILURE')",
            Long::class.java,
        )!!

    /** Aggregate active-send diagnostics, with no recipient or payload data leaving the repository. */
    fun sendingStats(): SmsSendingStats =
        jdbc.queryForObject(
            "SELECT count(*) AS count,min(updated_at) AS oldest FROM otp_deliveries WHERE state='SENDING'",
            { rs, _ -> SmsSendingStats(rs.getLong("count"), rs.getTimestamp("oldest")?.toInstant()) },
        )

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
                        rs.getTimestamp("next_attempt_at").toInstant(),
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
    fun recoverAndExpire(now: Instant, restart: Boolean, active: Set<String> = emptySet()) {
        val exclusion = if (active.isEmpty()) "" else "AND md5(phone_number) NOT IN (${active.joinToString { "?" }})"
        jdbc.update(
            "UPDATE otp_deliveries SET state='AMBIGUOUS',code=NULL,updated_at=? WHERE state='SENDING' AND (? OR expires_at<=?) $exclusion",
            Timestamp.from(now),
            *(listOf<Any>(restart, Timestamp.from(now)) + active).toTypedArray(),
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
    val nextAttemptAt: Instant,
)
