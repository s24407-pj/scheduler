package pl.kacosmetology.scheduler.notification

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import pl.kacosmetology.scheduler.auth.sms.SmsWork

/** JDBC outbox persistence; callers establish transaction boundaries before claiming or finishing a delivery. */
@Repository
class NotificationDeliveryRepository(private val jdbc: JdbcTemplate) {
    /** Picks the highest-priority due item per free company/customer lane without loading SMS content. */
    fun due(now: Instant, excluded: Set<String>, limit: Int): List<SmsWork> {
        if (limit == 0) return emptyList()
        val exclusion =
            if (excluded.isEmpty()) ""
            else "AND (d.company_id::text || ':' || d.customer_id::text) NOT IN (${excluded.joinToString { "?" }})"
        return jdbc.query(
            """
            SELECT * FROM (
                SELECT DISTINCT ON (d.company_id,d.customer_id) d.id,
                    d.company_id::text || ':' || d.customer_id::text AS lane,d.created_at,d.deadline,
                    CASE WHEN d.kind='REMINDER' THEN 0 ELSE 1 END AS priority
                FROM notification_deliveries d
                WHERE d.state IN ('PENDING','TEMPORARY_FAILURE') AND d.next_attempt_at<=?
                AND NOT EXISTS (SELECT 1 FROM notification_deliveries active
                    WHERE active.company_id=d.company_id AND active.customer_id=d.customer_id AND active.state='SENDING')
                $exclusion
                ORDER BY d.company_id,d.customer_id,priority,d.deadline,d.id
            ) candidates ORDER BY priority,deadline,id LIMIT ?
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

    /** Last observed durable backlog, including pending retries but excluding terminal and paused records. */
    fun pendingCount(): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM notification_deliveries WHERE state IN ('PENDING','TEMPORARY_FAILURE')",
            Long::class.java,
        )!!

    /** Locks one delivery so a provider attempt can be durably claimed once. */
    fun lock(id: UUID): Delivery? =
        jdbc
            .query("SELECT * FROM notification_deliveries WHERE id=? FOR UPDATE", { rs, _ -> read(rs) }, id)
            .singleOrNull()

    /** A duplicated application event must not invalidate its original successful or pending delivery. */
    fun hasStaffRevision(reservationId: UUID, revision: Long, kind: String): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM notification_deliveries WHERE reservation_id=? AND reservation_revision=? AND kind=?)",
            Boolean::class.java,
            reservationId,
            revision,
            kind,
        ) == true

    /** Inserts an outbox record; revision/date uniqueness prevents accidental duplicate events. */
    fun insert(
        kind: String,
        reservation: NotificationReservation,
        now: Instant,
        deadline: Instant,
        runToken: UUID? = null,
    ): UUID? =
        jdbc
            .query(
                """
                INSERT INTO notification_deliveries(kind,reservation_id,company_id,customer_id,reservation_revision,
                    appointment_date,state,next_attempt_at,deadline,run_token,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',?,?,?,?,?) ON CONFLICT DO NOTHING RETURNING id
                """
                    .trimIndent(),
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                kind,
                reservation.id,
                reservation.companyId,
                reservation.customerId,
                reservation.revision,
                reservation.date,
                Timestamp.from(now),
                Timestamp.from(deadline),
                runToken,
                Timestamp.from(now),
                Timestamp.from(now),
            )
            .singleOrNull()

    /** Persists one mandatory summary and its original affected reservation identifiers. */
    fun insertSummary(companyId: UUID, customerId: UUID, ids: List<UUID>, now: Instant) {
        if (ids.isEmpty()) return
        val id =
            jdbc.queryForObject(
                """
                INSERT INTO notification_deliveries(kind,company_id,customer_id,state,next_attempt_at,deadline,created_at,updated_at)
                VALUES ('BLOCK_SUMMARY',?,?,'PENDING',?,?,?,?) RETURNING id
                """
                    .trimIndent(),
                UUID::class.java,
                companyId,
                customerId,
                Timestamp.from(now),
                Timestamp.from(now.plusSeconds(86400)),
                Timestamp.from(now),
                Timestamp.from(now),
            )!!
        ids.distinct().forEach {
            jdbc.update(
                "INSERT INTO notification_delivery_reservations(delivery_id,reservation_id) VALUES (?,?)",
                id,
                it,
            )
        }
    }

    /** Removes only unsent obsolete staff notifications; an already claimed provider request may finish. */
    fun invalidateStaff(reservationId: UUID, now: Instant) {
        jdbc.update(
            "UPDATE notification_deliveries SET state='DISCARDED',updated_at=? WHERE reservation_id=? AND kind IN ('CREATE','EDIT','RESTORE','CANCEL') AND state IN ('PENDING','TEMPORARY_FAILURE','PAUSED')",
            Timestamp.from(now),
            reservationId,
        )
    }

    /** Updates state after preparation or a provider outcome. */
    fun state(id: UUID, state: DeliveryState, now: Instant, retry: Instant = now, deadline: Instant? = null) {
        jdbc.update(
            "UPDATE notification_deliveries SET state=?,updated_at=?,next_attempt_at=?,deadline=coalesce(?,deadline) WHERE id=?",
            state.name,
            Timestamp.from(now),
            Timestamp.from(retry),
            deadline?.let(Timestamp::from),
            id,
        )
    }

    /** Commits an attempt marker before the provider is called; crashes leave evidence instead of a retryable row. */
    fun sending(id: UUID, now: Instant, deadline: Instant) {
        jdbc.update(
            "UPDATE notification_deliveries SET state='SENDING',attempts=attempts+1,updated_at=?,deadline=? WHERE id=?",
            Timestamp.from(now),
            Timestamp.from(deadline),
            id,
        )
    }

    /**
     * Applies accepted/ambiguous reminder suppression to pending commercial confirmations for this appointment date.
     */
    fun suppressConfirmations(reservationId: UUID, date: LocalDate, revision: Long, ambiguous: Boolean, now: Instant) {
        jdbc.update(
            """
            UPDATE notification_deliveries SET state=?,updated_at=? WHERE reservation_id=? AND appointment_date=?
            AND kind IN ('CREATE','EDIT','RESTORE') AND state IN ('PENDING','TEMPORARY_FAILURE')
            AND (? OR reservation_revision<=?)
            """
                .trimIndent(),
            if (ambiguous) "PAUSED" else "DISCARDED",
            Timestamp.from(now),
            reservationId,
            date,
            ambiguous,
            revision,
        )
    }

    /** A previously accepted reminder supersedes older confirmations; an ambiguous one pauses same-date messages. */
    fun reminderEffect(reservationId: UUID, date: LocalDate, revision: Long): DeliveryState? =
        jdbc
            .query(
                """
                SELECT state,reservation_revision FROM notification_deliveries WHERE reservation_id=? AND appointment_date=?
                AND kind='REMINDER' AND state IN ('ACCEPTED','AMBIGUOUS')
                """
                    .trimIndent(),
                { rs, _ ->
                    if (rs.getString(1) == "AMBIGUOUS") DeliveryState.PAUSED
                    else if (rs.getLong(2) >= revision) DeliveryState.DISCARDED else null
                },
                reservationId,
                date,
            )
            .singleOrNull()

    /** Reads summary members; dispatch checks each member's latest tenant, status and start before every attempt. */
    fun summaryMembers(id: UUID): List<UUID> =
        jdbc
            .queryForList(
                "SELECT reservation_id FROM notification_delivery_reservations WHERE delivery_id=?",
                UUID::class.java,
                id,
            )
            .filterNotNull()

    /** Converts unknown in-flight sends on restart and drops reminder work whose uninterrupted run was lost. */
    fun recover(now: Instant) {
        jdbc.update(
            "UPDATE notification_deliveries SET state='AMBIGUOUS',updated_at=? WHERE state='SENDING'",
            Timestamp.from(now),
        )
        jdbc.update(
            "UPDATE notification_deliveries SET state='DISCARDED',updated_at=? WHERE kind='REMINDER' AND state IN ('PENDING','TEMPORARY_FAILURE')",
            Timestamp.from(now),
        )
        jdbc
            .query(
                "SELECT reservation_id,appointment_date,reservation_revision FROM notification_deliveries WHERE kind='REMINDER' AND state='AMBIGUOUS'",
                { rs, _ ->
                    Triple(rs.getObject(1, UUID::class.java), rs.getDate(2).toLocalDate(), rs.getLong(3))
                },
            )
            .forEach { suppressConfirmations(it.first, it.second, it.third, true, now) }
    }

    private fun read(rs: ResultSet) =
        Delivery(
            rs.getObject("id", UUID::class.java),
            rs.getString("kind"),
            rs.getObject("reservation_id", UUID::class.java),
            rs.getObject("company_id", UUID::class.java),
            rs.getObject("customer_id", UUID::class.java),
            rs.getObject("reservation_revision", Long::class.javaObjectType),
            rs.getDate("appointment_date")?.toLocalDate(),
            DeliveryState.valueOf(rs.getString("state")),
            rs.getInt("attempts"),
            rs.getTimestamp("deadline").toInstant(),
            rs.getObject("run_token", UUID::class.java),
            rs.getTimestamp("next_attempt_at").toInstant(),
        )
}

/** Durable notification states; only PENDING and TEMPORARY_FAILURE are automatically retryable. */
enum class DeliveryState {
    PENDING,
    SENDING,
    ACCEPTED,
    TEMPORARY_FAILURE,
    AMBIGUOUS,
    PAUSED,
    DISCARDED,
}

/** Scalar durable work item, independent of mutable ORM entities and sessions. */
data class Delivery(
    val id: UUID,
    val kind: String,
    val reservationId: UUID?,
    val companyId: UUID,
    val customerId: UUID,
    val revision: Long?,
    val date: LocalDate?,
    val state: DeliveryState,
    val attempts: Int,
    val deadline: Instant,
    val runToken: UUID?,
    val nextAttemptAt: Instant,
)
