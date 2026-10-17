package pl.kacosmetology.scheduler.reservation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import jakarta.servlet.http.Cookie
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import pl.kacosmetology.scheduler.security.TestSessions
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Shared real-schema fixtures preserve independent realms, revision preconditions and durable submission identity. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
abstract class ReservationContractTestSupport {
    @Autowired protected lateinit var mvc: MockMvc
    @Autowired protected lateinit var jdbc: JdbcTemplate
    @Autowired protected lateinit var json: ObjectMapper
    @Autowired protected lateinit var fixtures: ContractFixtures
    @Autowired protected lateinit var sessions: TestSessions
    @Autowired protected lateinit var customers: UserRepository
    @Autowired protected lateinit var staffAccounts: CompanyEmployeeRepository
    @Autowired protected lateinit var repository: ReservationRepository
    @Autowired protected lateinit var slotPolicy: pl.kacosmetology.scheduler.availability.BookableSlotPolicy
    @Autowired protected lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager
    @MockkBean protected lateinit var clock: Clock
    @MockkBean protected lateinit var sms: SmsSender
    @MockkBean protected lateinit var storage: S3Client
    protected lateinit var salon: ContractFixtures.Salon
    protected val initialNow: Instant = Instant.parse("2030-01-14T10:00:00Z")
    protected val future: LocalDateTime = LocalDateTime.parse("2030-01-14T12:00:00")
    protected val localNow: LocalDateTime = LocalDateTime.parse("2030-01-14T11:00:00")

    @BeforeEach
    fun contractSetup() {
        setNow(initialNow)
        every { clock.zone } returns BUSINESS_ZONE
        salon = fixtures.salon()
    }

    protected fun setNow(value: Instant) {
        every { clock.instant() } returns value
    }

    protected fun setLocalNow(value: LocalDateTime) = setNow(value.atZone(BUSINESS_ZONE).toInstant())

    protected fun customerCookie(id: UUID = salon.customer): Cookie =
        sessions.customer(customers.findById(id).orElseThrow())

    protected fun staffCookie(id: UUID = salon.staff): Cookie =
        staffAccounts.findById(id).orElseThrow().let {
            sessions.staff(User(id = it.userId, phoneNumber = "+48999000111"), it)
        }

    protected fun newCustomer(): UUID =
        jdbc.queryForObject(
            "INSERT INTO customer_accounts(phone_number) VALUES(?) RETURNING id",
            UUID::class.java,
            "+48" + (100000000..999999999).random(),
        )!!

    protected fun newEmployee(companyId: UUID = salon.company, role: String = "EMPLOYEE"): Pair<UUID, UUID> {
        val public = jdbc.queryForObject("SELECT uuidv7()", UUID::class.java)!!
        val account =
            jdbc.queryForObject(
                "INSERT INTO company_employees(company_id,user_id,role,login_email,first_name,last_name) VALUES(?,?,?,?,'Other','Employee') RETURNING id",
                UUID::class.java,
                companyId,
                public,
                role,
                "${UUID.randomUUID()}@example.test",
            )!!
        return public to account
    }

    protected fun shift(
        employee: UUID = salon.employee,
        start: String = "08:00",
        end: String = "20:00",
        date: LocalDateTime = future,
    ) {
        jdbc.update(
            "INSERT INTO employee_work_schedules(company_id,employee_id,day_of_week,start_time,end_time) VALUES(?,?,?,?::time,?::time) ON CONFLICT(company_id,employee_id,day_of_week) DO UPDATE SET start_time=excluded.start_time,end_time=excluded.end_time",
            salon.company,
            employee,
            date.dayOfWeek.name,
            start,
            end,
        )
    }

    protected fun customerBody(
        start: LocalDateTime = future,
        employee: UUID? = salon.employee,
        service: UUID = salon.service,
    ): Map<String, Any?> =
        mapOf(
            "employeeId" to employee,
            "serviceId" to service,
            "startTime" to start.toString(),
            "firstName" to "Jan",
            "lastName" to "Klient",
        )

    protected fun book(
        body: Any = customerBody(),
        key: String = UUID.randomUUID().toString(),
        cookie: Cookie = customerCookie(),
    ): MvcResult = postJson("/api/reservations", body, cookie, key)

    protected fun manual(
        body: Any = manualBody(),
        key: String = UUID.randomUUID().toString(),
        cookie: Cookie = staffCookie(),
    ): MvcResult = postJson("/api/reservations/staff", body, cookie, key)

    protected fun manualBody(
        start: LocalDateTime = future,
        employee: UUID = salon.employee,
        service: UUID = salon.service,
        phone: String = phone(salon.customer),
    ): Map<String, Any?> =
        mapOf(
            "employeeId" to employee,
            "serviceId" to service,
            "startTime" to start.toString(),
            "customerPhone" to phone,
            "customerFirstName" to "Jan",
            "customerLastName" to "Klient",
        )

    protected fun phone(customer: UUID): String =
        jdbc.queryForObject("SELECT phone_number FROM customer_accounts WHERE id=?", String::class.java, customer)!!

    protected fun postJson(path: String, body: Any, cookie: Cookie?, key: String? = null): MvcResult {
        val request = post(path).with(csrf()).contentType("application/json").content(json.writeValueAsString(body))
        cookie?.let { request.cookie(it) }
        key?.let { request.header("Idempotency-Key", it) }
        return mvc.perform(request).andReturn()
    }

    protected fun action(
        id: UUID,
        operation: String,
        revision: Long = version(id),
        body: Any = emptyMap<String, String>(),
        cookie: Cookie = staffCookie(),
    ): MvcResult =
        mvc.perform(
                patch("/api/reservations/$id/$operation")
                    .with(csrf())
                    .cookie(cookie)
                    .header("If-Match", "\"$revision\"")
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)),
            )
            .andReturn()

    protected fun edit(id: UUID, body: Any, revision: Long = version(id), cookie: Cookie = staffCookie()): MvcResult =
        mvc.perform(
                put("/api/reservations/$id")
                    .with(csrf())
                    .cookie(cookie)
                    .header("If-Match", "\"$revision\"")
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)),
            )
            .andReturn()

    protected fun id(result: MvcResult): UUID = UUID.fromString(tree(result)["id"].asString())

    protected fun tree(result: MvcResult): JsonNode = json.readTree(result.response.contentAsString)

    protected fun version(id: UUID): Long =
        jdbc.queryForObject("SELECT version FROM reservations WHERE id=?", Long::class.java, id)!!

    protected fun state(id: UUID): String =
        jdbc.queryForObject("SELECT status FROM reservations WHERE id=?", String::class.java, id)!!

    protected fun count(table: String): Long = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

    protected fun profile(customer: UUID = salon.customer) = fixtures.profile(salon, customer)

    protected fun reservation(
        start: LocalDateTime = future,
        status: String = "BOOKED",
        customer: UUID = salon.customer,
        employee: UUID = salon.employee,
    ): UUID = fixtures.reservation(salon, start, status, customer, employee)

    protected fun block(
        employee: UUID = salon.employee,
        start: LocalDateTime = future,
        end: LocalDateTime = start.plusHours(1),
    ) {
        jdbc.update(
            "INSERT INTO schedule_blocks(company_id,employee_id,start_time,end_time) VALUES(?,?,?,?)",
            salon.company,
            employee,
            start.atZone(BUSINESS_ZONE).toOffsetDateTime(),
            end.atZone(BUSINESS_ZONE).toOffsetDateTime(),
        )
    }
}
