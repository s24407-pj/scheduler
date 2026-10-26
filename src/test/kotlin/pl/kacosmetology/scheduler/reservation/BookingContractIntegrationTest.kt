package pl.kacosmetology.scheduler.reservation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import pl.kacosmetology.scheduler.security.StaffFixture
import pl.kacosmetology.scheduler.security.TestSessions
import pl.kacosmetology.scheduler.user.CompanyCustomerRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

/** End-to-end booking submission, agreement, revision, lifecycle and hard concurrency guarantees. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class BookingContractIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var fixtures: ContractFixtures
    @Autowired private lateinit var sessions: TestSessions
    @Autowired private lateinit var customers: CompanyCustomerRepository
    @Autowired private lateinit var employees: CompanyEmployeeRepository
    @MockkBean private lateinit var clock: Clock
    @MockkBean private lateinit var sms: SmsSender
    @MockkBean private lateinit var storage: S3Client
    private lateinit var salon: ContractFixtures.Salon
    private val now = Instant.parse("2030-01-14T10:00:00Z")
    private val start = LocalDateTime.parse("2030-01-14T12:00:00")

    @BeforeEach
    fun setup() {
        every { clock.instant() } returns now
        every { clock.zone } returns BUSINESS_ZONE
        salon = fixtures.salon()
    }

    private fun customer() = sessions.customer(customers.findById(salon.customer).orElseThrow())

    private fun staff() =
        employees.findById(salon.staff).orElseThrow().let {
            sessions.staff(StaffFixture(id = it.userId, phoneNumber = "+48999000111"), it)
        }

    private fun payload(employee: UUID? = salon.employee) =
        mapOf(
            "employeeId" to employee,
            "serviceId" to salon.service,
            "startTime" to start.toString(),
            "firstVisitDetails" to mapOf("firstName" to "Jan", "lastName" to "Klient"),
        )

    private fun book(key: String, body: Any = payload(), cookie: jakarta.servlet.http.Cookie = customer()): MvcResult =
        mvc.perform(
                post("/api/reservations")
                    .cookie(cookie)
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)),
            )
            .andReturn()

    private fun action(id: UUID, operation: String, revision: Long, body: Any = emptyMap<String, String>()): MvcResult =
        mvc.perform(
                patch("/api/reservations/$id/$operation")
                    .cookie(staff())
                    .with(csrf())
                    .header("If-Match", "\"$revision\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)),
            )
            .andReturn()

    private fun id(result: MvcResult) = UUID.fromString(json.readTree(result.response.contentAsString)["id"].asString())

    @Test
    fun `failed submission binds payload and creates profile only when later same request succeeds`() {
        val key = UUID.randomUUID().toString()
        assertEquals(409, book(key).response.status)
        assertEquals(
            0L,
            jdbc.queryForObject<Long>("SELECT count(*) FROM company_customers WHERE first_booking_at IS NOT NULL"),
        )
        assertEquals(409, book(key, payload() + mapOf("firstName" to "Zmienione")).response.status)
        fixtures.shift(salon, start)
        val created = book(key)
        assertEquals(201, created.response.status, created.response.contentAsString)
        val replay = book(key)
        assertEquals(200, replay.response.status)
        assertEquals(id(created), id(replay))
        val body = json.readTree(created.response.contentAsString)
        assertEquals("BOOKED", body["status"].stringValue())
        assertEquals("Strzyżenie", body["serviceName"].stringValue())
        assertFalse(body["createdAt"].isNull)
        assertEquals(salon.company, UUID.fromString(body["companyId"].asString()))
        assertEquals(
            1L,
            jdbc.queryForObject<Long>("SELECT count(*) FROM company_customers WHERE first_booking_at IS NOT NULL"),
        )
    }

    @Test
    fun `replay follows edits cancellation and archive while stale reversed action conflicts`() {
        fixtures.shift(salon, start)
        val key = UUID.randomUUID().toString()
        val created = book(key)
        assertEquals(201, created.response.status, created.response.contentAsString)
        val id = id(created)
        jdbc.update("UPDATE offerings SET price=500,duration_minutes=90,name='Nowa nazwa' WHERE id=?", salon.service)
        val edit =
            mvc.perform(
                    put("/api/reservations/$id")
                        .cookie(staff())
                        .with(csrf())
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"startTime":"2030-01-14T14:00:00"}"""),
                )
                .andReturn()
        assertEquals(200, edit.response.status, edit.response.contentAsString)
        val edited = json.readTree(edit.response.contentAsString)
        assertEquals(101, edited["price"].intValue())
        assertEquals(30, edited["durationMinutes"].intValue())
        assertEquals("Strzyżenie", edited["serviceName"].stringValue())
        assertEquals(json.readTree(created.response.contentAsString)["createdAt"], edited["createdAt"])
        assertEquals(204, action(id, "cancel-by-staff", 1, mapOf("reason" to "Tylko dla personelu")).response.status)
        assertEquals(204, action(id, "cancel-by-staff", 1, mapOf("reason" to "Tylko dla personelu")).response.status)
        assertEquals(204, action(id, "restore", 2).response.status)
        assertEquals(409, action(id, "cancel-by-staff", 1, mapOf("reason" to "Tylko dla personelu")).response.status)
        assertEquals(409, action(id, "archive", 3).response.status)
        assertEquals(204, action(id, "cancel-by-staff", 3, mapOf("reason" to "Ponowne anulowanie")).response.status)
        assertEquals(204, action(id, "archive", 4).response.status)
        val replay = book(key)
        assertEquals(200, replay.response.status)
        val current = json.readTree(replay.response.contentAsString)
        assertTrue(current["archived"].booleanValue())
        assertEquals("CANCELLED", current["status"].stringValue())
        assertNull(current["internalCancellationReason"])
        val list = mvc.perform(get("/api/reservations/me").cookie(customer())).andReturn()
        assertEquals(0, json.readTree(list.response.contentAsString).size())
        assertEquals(409, action(id, "restore", 5).response.status)
        assertEquals(204, action(id, "unarchive", 5).response.status)
        assertEquals(204, action(id, "restore", 6).response.status)
        assertTrue(
            jdbc.queryForObject<Long>(
                "SELECT count(*) FROM reservation_audit WHERE reservation_id=?",
                id,
            )!! >= 8,
        )
    }

    @Test
    fun `simultaneous different employee requests cannot overlap the same customer`() {
        fixtures.shift(salon, start)
        val other = jdbc.queryForObject<UUID>("SELECT uuidv7()")!!
        jdbc.update(
            "INSERT INTO company_employees(company_id,user_id,role,first_name,last_name) VALUES(?,?,'EMPLOYEE','Inny','Pracownik')",
            salon.company,
            other,
        )
        jdbc.update(
            "INSERT INTO employee_work_schedules(company_id,employee_id,day_of_week,start_time,end_time) VALUES(?,?,'MONDAY','08:00','20:00')",
            salon.company,
            other,
        )
        val cookie = customer()
        val ready = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val calls =
                listOf(salon.employee, other).map { employee ->
                    pool.submit<Int> {
                        ready.await()
                        book(UUID.randomUUID().toString(), payload(employee), cookie).response.status
                    }
                }
            ready.countDown()
            assertEquals(listOf(201, 409), calls.map { it.get(15, TimeUnit.SECONDS) }.sorted())
            assertEquals(1L, jdbc.queryForObject<Long>("SELECT count(*) FROM reservations"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `same key concurrent submissions create one reservation and current replay`() {
        fixtures.shift(salon, start)
        val cookie = customer()
        val key = UUID.randomUUID().toString()
        val ready = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val calls =
                (1..2).map {
                    pool.submit<MvcResult> {
                        ready.await()
                        book(key, cookie = cookie)
                    }
                }
            ready.countDown()
            val results = calls.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(listOf(200, 201), results.map { it.response.status }.sorted())
            assertEquals(id(results[0]), id(results[1]))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `missing idempotency key fractional id and unknown field are rejected`() {
        val cookie = customer()
        listOf(
                json.writeValueAsString(payload()).replace("\"${salon.service}\"", "100.9,"),
                json.writeValueAsString(payload() + mapOf("unexpected" to true)),
            )
            .forEach { body ->
                assertEquals(
                    400,
                    mvc.perform(
                            post("/api/reservations")
                                .cookie(cookie)
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        )
                        .andReturn()
                        .response
                        .status,
                )
            }
        assertEquals(
            400,
            mvc.perform(
                    post("/api/reservations")
                        .cookie(cookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload())),
                )
                .andReturn()
                .response
                .status,
        )
    }

    @Test
    fun `invalid DTO and unknown field submissions remain bound to their original payload`() {
        fixtures.shift(salon, start)
        val key = UUID.randomUUID().toString()
        assertEquals(400, book(key, payload() + mapOf("employeeId" to -1)).response.status)
        assertEquals(409, book(key).response.status)
        val other = UUID.randomUUID().toString()
        assertEquals(400, book(other, payload() + mapOf("unknown" to true)).response.status)
        assertEquals(409, book(other).response.status)
    }
}
