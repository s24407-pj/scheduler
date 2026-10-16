package pl.kacosmetology.scheduler.reservation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import software.amazon.awssdk.services.s3.S3Client

/** Restart catch-up must audit/version every completion and retain its full occupied interval. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AutoCompleteIntegrationTest {
    @Autowired private lateinit var fixtures: ContractFixtures
    @Autowired private lateinit var reservations: ReservationRepository
    @Autowired private lateinit var service: ReservationService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockkBean private lateinit var clock: Clock
    @MockkBean private lateinit var sms: SmsSender
    @MockkBean private lateinit var storage: S3Client
    private val now = Instant.parse("2030-01-14T10:00:00Z")

    @BeforeEach
    fun time() {
        every { clock.instant() } returns now
        every { clock.zone } returns BUSINESS_ZONE
    }

    @Test
    fun `catch up audits each elapsed booking and preserves interval and version`() {
        val salon = fixtures.salon()
        val start = LocalDateTime.ofInstant(now, BUSINESS_ZONE).minusHours(3)
        val id = fixtures.reservation(salon, start)
        assertEquals(1, service.autoCompleteElapsed())
        val result = reservations.findById(id).orElseThrow()
        assertEquals(ReservationStatus.COMPLETED, result.status)
        assertEquals(start.plusMinutes(30), result.endTime)
        assertEquals(1, result.version)
        assertEquals(
            2L,
            jdbc.queryForObject("SELECT count(*) FROM reservation_audit WHERE reservation_id=?", Long::class.java, id),
        )
        assertEquals(0, service.autoCompleteElapsed())
        assertThrows(Exception::class.java) { fixtures.reservation(salon, start.plusMinutes(15)) }
    }

    @Test
    fun `future cancelled and no show records are untouched`() {
        val salon = fixtures.salon()
        val local = LocalDateTime.ofInstant(now, BUSINESS_ZONE)
        fixtures.reservation(salon, local.plusHours(1))
        fixtures.reservation(salon, local.minusHours(4), "CANCELLED")
        fixtures.reservation(salon, local.minusHours(3), "NO_SHOW")
        assertEquals(0, service.autoCompleteElapsed())
    }
}
