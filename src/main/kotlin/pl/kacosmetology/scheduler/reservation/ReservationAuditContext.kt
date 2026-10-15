package pl.kacosmetology.scheduler.reservation

import java.time.Clock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import pl.kacosmetology.scheduler.security.CustomUserDetails

/** Supplies transaction-local actor metadata to the database's durable reservation audit trigger. */
@Component
class ReservationAuditContext(private val jdbc: JdbcTemplate, private val clock: Clock) {
    /** Captures the request account, or SYSTEM for background work, on the current transaction connection. */
    fun capture() {
        val actor = SecurityContextHolder.getContext().authentication?.principal as? CustomUserDetails
        val type =
            when (actor?.role) {
                "ROLE_CUSTOMER" -> "CUSTOMER"
                "ROLE_OWNER",
                "ROLE_EMPLOYEE" -> "STAFF"
                else -> "SYSTEM"
            }
        val account = actor?.let { if (type == "STAFF") "staff:${it.employmentId}" else "customer:${it.id}" } ?: ""
        jdbc.queryForObject("SELECT set_config('scheduler.actor_type', ?, true)", String::class.java, type)
        jdbc.queryForObject("SELECT set_config('scheduler.actor_account', ?, true)", String::class.java, account)
        jdbc.queryForObject(
            "SELECT set_config('scheduler.audit_time', ?, true)",
            String::class.java,
            clock.instant().toString(),
        )
    }
}
