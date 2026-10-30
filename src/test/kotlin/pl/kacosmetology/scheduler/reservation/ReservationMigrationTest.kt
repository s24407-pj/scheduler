package pl.kacosmetology.scheduler.reservation

import java.time.LocalDateTime
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer

/** Upgrade coverage preserves reservation history while removing obsolete persistence fields. */
class ReservationMigrationTest {
    @Test
    fun `V3 upgrades populated V2 without changing reservations or historical audit`() {
        PostgreSQLContainer("postgres:18-alpine").use { postgres ->
            postgres.start()
            val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            Flyway.configure().dataSource(source).target("2").load().migrate()
            val jdbc = JdbcTemplate(source)
            val fixtures = ContractFixtures(jdbc)
            val salon = fixtures.salon()
            val id = fixtures.reservation(salon, LocalDateTime.parse("2030-06-03T12:00:00"))
            jdbc.update(
                "UPDATE reservations SET creation_source='LEGACY',reminder_sent=true,service_name_source='RECONSTRUCTED' WHERE id=?",
                id,
            )
            val audit = jdbc.queryForList("SELECT * FROM reservation_audit WHERE reservation_id=? ORDER BY id", id)
            val row = jdbc.queryForMap("SELECT * FROM reservations WHERE id=?", id).toMutableMap()
            row.remove("reminder_sent")
            row.remove("service_name_source")

            Flyway.configure().dataSource(source).load().migrate()

            assertEquals(row, jdbc.queryForMap("SELECT * FROM reservations WHERE id=?", id))
            assertEquals(
                audit,
                jdbc.queryForList("SELECT * FROM reservation_audit WHERE reservation_id=? ORDER BY id", id),
            )
            val columns =
                jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='reservations'",
                    String::class.java,
                )
            assertFalse("reminder_sent" in columns)
            assertFalse("service_name_source" in columns)
            assertThrows(DataIntegrityViolationException::class.java) {
                jdbc.update(
                    "INSERT INTO reservations(company_id,customer_id,employee_id,service_id,price,start_time,end_time,status,accepted_service_name,agreed_duration_minutes) SELECT company_id,customer_id,employee_id,service_id,price,start_time,end_time,'CANCELLED',accepted_service_name,agreed_duration_minutes FROM reservations WHERE id=?",
                    id,
                )
            }
        }
    }
}
