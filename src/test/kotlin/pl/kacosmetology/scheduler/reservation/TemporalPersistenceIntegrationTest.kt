package pl.kacosmetology.scheduler.reservation

import com.ninjasquad.springmockk.MockkBean
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.availability.BookableSlotPolicy
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkScheduleRepository
import software.amazon.awssdk.services.s3.S3Client

/** SQL TIME stays local while TIMESTAMPTZ round-trips Warsaw instants with the whole JVM configured UTC. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class TemporalPersistenceIntegrationTest {
    @Autowired private lateinit var fixtures: ContractFixtures
    @Autowired private lateinit var companies: CompanyRepository
    @Autowired private lateinit var weekly: EmployeeWorkScheduleRepository
    @Autowired private lateinit var policy: BookableSlotPolicy
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var reservations: ReservationRepository
    @MockkBean private lateinit var smsSender: SmsSender
    @MockkBean private lateinit var s3Client: S3Client

    @Test
    fun `Warsaw timestamps and local shift hours preserve exact values under UTC JVM`() {
        assertEquals("UTC", System.getProperty("user.timezone"))
        val salon = fixtures.salon()
        val date = LocalDateTime.parse("2030-04-14T19:30:00")
        fixtures.shift(salon, date)
        val shift = policy.effectiveShift(salon.company, salon.employee, date.toLocalDate())!!
        assertEquals(LocalTime.of(8, 0), shift.startTime)
        assertEquals(LocalTime.of(20, 0), shift.endTime)
        assertTrue(policy.isOnGrid(salon.company, salon.employee, date, 30, 30))
        val company = companies.findById(salon.company).orElseThrow()
        company.openingTime = LocalTime.of(8, 15)
        companies.saveAndFlush(company)
        assertEquals(
            "08:15:00",
            jdbc.queryForObject<String>(
                "SELECT opening_time::text FROM companies WHERE id=?",
                salon.company,
            ),
        )
        assertEquals(LocalTime.of(8, 15), companies.findById(salon.company).orElseThrow().openingTime)
        val id = fixtures.reservation(salon, date)
        assertEquals(date, reservations.findById(id).orElseThrow().startTime)
        assertEquals(date.plusMinutes(30), reservations.findById(id).orElseThrow().endTime)
    }
}
