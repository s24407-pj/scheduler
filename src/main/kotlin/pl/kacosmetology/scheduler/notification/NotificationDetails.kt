package pl.kacosmetology.scheduler.notification

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE

/** Loads company-local presentation and accepted commercial names without reading legacy global user profiles. */
@Component
class NotificationDetails(private val jdbc: JdbcTemplate) {
    /** Reads a current reservation and independently scoped account/company/employee presentation. */
    fun reservation(id: UUID): NotificationReservation? =
        jdbc
            .query(
                """
                SELECT r.*, c.phone_number AS customer_phone, co.name AS company_name,
                    e.first_name AS employee_first_name, e.last_name AS employee_last_name
                FROM reservations r JOIN customer_accounts c ON c.id=r.customer_id
                JOIN companies co ON co.id=r.company_id
                JOIN company_employees e ON e.company_id=r.company_id AND e.user_id=r.employee_id
                WHERE r.id=?
                """
                    .trimIndent(),
                { rs, _ -> read(rs) },
                id,
            )
            .singleOrNull()

    /** Lists booked candidates for the next Warsaw calendar date, independent of the old reminder boolean. */
    fun reminderCandidates(date: LocalDate): List<UUID> =
        jdbc
            .queryForList(
                """
                SELECT id FROM reservations WHERE status='BOOKED' AND NOT archived
                AND (start_time AT TIME ZONE 'Europe/Warsaw')::date=? ORDER BY id
                """
                    .trimIndent(),
                UUID::class.java,
                date,
            )
            .filterNotNull()

    /**
     * A creation or appointment start change after 09:00 excludes the record from this day's reminder run. Other edits
     * do not reset reminder identity or cut-off eligibility. Durable audit is authoritative after rescheduling.
     */
    fun eligibleAtCutoff(id: UUID, cutoff: Instant): Boolean =
        jdbc.queryForObject(
            """
            SELECT r.created_at<=? AND NOT EXISTS (
                SELECT 1 FROM reservation_audit a WHERE a.reservation_id=r.id AND a.occurred_at>?
                AND (a.before_state IS NULL OR
                    ((a.before_state->>'start_time')::timestamptz IS DISTINCT FROM (a.after_state->>'start_time')::timestamptz))
            ) FROM reservations r WHERE r.id=?
            """
                .trimIndent(),
            Boolean::class.java,
            Timestamp.from(cutoff),
            Timestamp.from(cutoff),
            id,
        ) == true

    /** Renders current presentation, historical accepted service name and the latest appointment interval. */
    fun message(kind: String, reservation: NotificationReservation): String {
        val date = reservation.start.atZone(BUSINESS_ZONE).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
        return when (kind) {
            "CANCEL" -> "Rezerwacja odwołana: ${reservation.serviceName} $date. Salon ${reservation.companyName}."
            "REMINDER" ->
                "Przypomnienie: jutro $date — ${reservation.serviceName} u ${reservation.employeeName}. Salon ${reservation.companyName}."
            "EDIT" ->
                "Zmiana wizyty: ${reservation.serviceName} u ${reservation.employeeName} — $date. Salon ${reservation.companyName}."
            "RESTORE" ->
                "Wizyta przywrócona: ${reservation.serviceName} u ${reservation.employeeName} — $date. Salon ${reservation.companyName}."
            else ->
                "Wizyta zarezerwowana: ${reservation.serviceName} u ${reservation.employeeName} — $date. Salon ${reservation.companyName}."
        }
    }

    /** Produces one company-scoped summary from only the bookings still cancelled and future at dispatch. */
    fun summary(reservations: List<NotificationReservation>): String {
        val first = reservations.first()
        val visits =
            reservations
                .sortedBy { it.start }
                .joinToString("; ") {
                    "${it.serviceName} ${it.start.atZone(BUSINESS_ZONE).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))}"
                }
        return "Odwołane wizyty w salonie ${first.companyName}: $visits."
    }

    private fun read(rs: ResultSet): NotificationReservation {
        val start = rs.getTimestamp("start_time").toInstant()
        return NotificationReservation(
            rs.getObject("id", UUID::class.java),
            rs.getObject("company_id", UUID::class.java),
            rs.getObject("customer_id", UUID::class.java),
            rs.getLong("version"),
            start,
            rs.getTimestamp("end_time").toInstant(),
            start.atZone(BUSINESS_ZONE).toLocalDate(),
            rs.getString("status"),
            rs.getBoolean("archived"),
            rs.getString("customer_phone"),
            rs.getString("accepted_service_name"),
            "${rs.getString("employee_first_name")} ${rs.getString("employee_last_name")}",
            rs.getString("company_name"),
        )
    }
}

/** Current dispatch details, combining scalar identity, scheduling and company-local presentation. */
data class NotificationReservation(
    val id: UUID,
    val companyId: UUID,
    val customerId: UUID,
    val revision: Long,
    val start: Instant,
    val end: Instant,
    val date: LocalDate,
    val status: String,
    val archived: Boolean,
    val phone: String,
    val serviceName: String,
    val employeeName: String,
    val companyName: String,
)
