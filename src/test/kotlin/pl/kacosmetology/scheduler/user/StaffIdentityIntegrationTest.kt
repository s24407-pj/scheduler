package pl.kacosmetology.scheduler.user

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import jakarta.servlet.http.Cookie
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
import org.springframework.session.data.redis.RedisIndexedSessionRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.AuthService
import pl.kacosmetology.scheduler.auth.dto.StaffLoginRequest
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.security.TestSessions
import pl.kacosmetology.scheduler.testId
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

/** HTTP tests exercise real account/session persistence and real transaction commit listeners. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class StaffIdentityIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var companies: CompanyRepository
    @Autowired private lateinit var accounts: CompanyEmployeeRepository
    @Autowired private lateinit var customers: UserRepository
    @Autowired private lateinit var profiles: CompanyCustomerRepository
    @Autowired private lateinit var links: StaffPasswordLinkRepository
    @Autowired private lateinit var passwords: PasswordEncoder
    @Autowired private lateinit var fixtures: TestSessions
    @Autowired private lateinit var auth: AuthService
    @Autowired private lateinit var sessions: RedisIndexedSessionRepository
    @Autowired private lateinit var offerings: pl.kacosmetology.scheduler.offering.OfferingRepository
    @Autowired private lateinit var bookings: pl.kacosmetology.scheduler.reservation.ReservationRepository
    @MockkBean private lateinit var sms: SmsSender
    @MockkBean private lateinit var s3: S3Client
    @MockkBean private lateinit var clock: Clock
    private lateinit var company: Company
    private lateinit var otherCompany: Company
    private lateinit var owner: Cookie
    private val now = Instant.parse("2026-10-15T10:00:00Z")

    @BeforeEach
    fun setup() {
        jdbc.execute("TRUNCATE companies, customer_accounts RESTART IDENTITY CASCADE")
        redis.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
        every { clock.instant() } returns now
        every { clock.zone } returns ZoneId.of("Europe/Warsaw")
        company = companies.save(Company(name = "First"))
        otherCompany = companies.save(Company(name = "Other"))
        val account =
            accounts.save(
                CompanyEmployee(
                    companyId = company.id!!,
                    userId = testId(101),
                    role = "OWNER",
                    loginEmail = "owner@example.test",
                    passwordHash = passwords.encode("password123"),
                    firstName = "Owner",
                    lastName = "One",
                ),
            )
        owner = login(account.companyId, "owner@example.test")
    }

    @Test
    fun `owner creates independent employee and single use 24h setup link`() {
        val created = create()
        assertEquals(0, customers.count())
        val token = created["passwordSetup"]["token"].asString()
        assertEquals(now.plusSeconds(86400).toString(), created["passwordSetup"]["expiresAt"].asString())
        assertNotEquals(token, links.findAll().single().tokenHash)
        val set = postJson("/api/auth/staff-password", mapOf("token" to token, "newPassword" to "password123"))
        assertEquals(204, set.response.status)
        assertNull(set.response.getCookie("SESSION"))
        assertEquals(
            401,
            postJson("/api/auth/staff-password", mapOf("token" to token, "newPassword" to "password456"))
                .response
                .status,
        )
        val cookie = login(company.id!!, "  EMPLOYEE@EXAMPLE.TEST  ")
        val profile = mvc.perform(get("/api/staff/me").cookie(cookie)).andReturn().response
        assertEquals("Local", mapper.readTree(profile.contentAsString)["firstName"].asString())
        assertEquals(403, mvc.perform(get("/api/users/me").cookie(cookie)).andReturn().response.status)
    }

    @Test
    fun `replacement expiry and disable invalidate old password links`() {
        val created = create()
        val id = UUID.fromString(created["employee"]["id"].asString())
        val old = created["passwordSetup"]["token"].asString()
        val fresh =
            mapper
                .readTree(postJson("/api/employees/$id/password-link", null, owner).response.contentAsString)["token"]
                .asString()
        assertEquals(401, consume(old))
        every { clock.instant() } returns now.plusSeconds(86400)
        assertEquals(401, consume(fresh))
        every { clock.instant() } returns now
        val next =
            mapper
                .readTree(postJson("/api/employees/$id/password-link", null, owner).response.contentAsString)["token"]
                .asString()
        assertEquals(200, putJson("/api/employees/$id/enabled", mapOf("enabled" to false), owner).response.status)
        assertEquals(200, putJson("/api/employees/$id/enabled", mapOf("enabled" to true), owner).response.status)
        assertEquals(401, consume(next))
    }

    @Test
    fun `password change validates current password and revokes only that company account`() {
        val employee = employee(company.id!!)
        val foreign = employee(otherCompany.id!!)
        val first = login(employee.companyId, "employee@example.test")
        val second = login(employee.companyId, "employee@example.test")
        val untouched = login(foreign.companyId, "employee@example.test")
        val customer = customers.save(User(phoneNumber = "+48500100200"))
        val customerSession = fixtures.customer(customer)
        assertEquals(
            401,
            postJson(
                    "/api/staff/password",
                    mapOf("currentPassword" to "wrongpass", "newPassword" to "password456"),
                    first,
                )
                .response
                .status,
        )
        assertEquals(200, me(first))
        val result =
            postJson(
                "/api/staff/password",
                mapOf("currentPassword" to "password123", "newPassword" to "password456"),
                first,
            )
        assertEquals(204, result.response.status)
        assertNull(result.response.getCookie("SESSION"))
        assertEquals(401, me(first))
        assertEquals(401, me(second))
        assertEquals(200, me(untouched))
        assertEquals(200, me(customerSession))
        assertEquals(
            401,
            postJson(
                    "/api/auth/login-staff",
                    mapOf("companyId" to company.id, "email" to "employee@example.test", "password" to "password123"),
                )
                .response
                .status,
        )
        login(company.id!!, "employee@example.test", "password456")
    }

    @Test
    fun `reset revokes sessions and rejects an old password login saved after reset`() {
        val employee = employee(company.id!!)
        val existing = login(company.id!!, "employee@example.test")
        val pending =
            auth.loginStaff(StaffLoginRequest("employee@example.test", "password123", company.id), "127.0.0.2")
        val issued = postJson("/api/employees/${employee.userId}/password-link", null, owner)
        assertEquals(200, issued.response.status)
        val token = mapper.readTree(issued.response.contentAsString)["token"].asString()
        assertEquals(204, consume(token, "password456"))
        val delayed = sessions.createSession()
        pending.sessionAttributes.forEach { (key, value) -> delayed.setAttribute(key, value) }
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken.authenticated(pending.principal, null, pending.principal.authorities)
        delayed.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context)
        sessions.save(delayed)
        val delayedCookie = Cookie("SESSION", Base64.getEncoder().encodeToString(delayed.id.toByteArray()))
        assertEquals(401, me(delayedCookie))
        assertEquals(401, me(existing))
        login(company.id!!, "employee@example.test", "password456")
    }

    @Test
    fun `company customer read notes and profile writes require legitimate local relationship`() {
        val account = customers.save(User(phoneNumber = "+48500100200"))
        profiles.save(
            CompanyCustomer(
                companyId = otherCompany.id!!,
                userId = account.id!!,
                firstName = "Private",
                lastName = "Other",
                notes = "secret",
            ),
        )
        assertEquals(404, mvc.perform(get("/api/customers/${account.id}").cookie(owner)).andReturn().response.status)
        assertEquals(
            404,
            putJson("/api/customers/${account.id}/notes", mapOf("notes" to "intrusion"), owner).response.status,
        )
        assertEquals(1, profiles.count())
        profiles.save(
            CompanyCustomer(companyId = company.id!!, userId = account.id!!, firstName = "Local", lastName = "Name"),
        )
        assertEquals(
            204,
            putJson(
                    "/api/customers/${account.id}/profile",
                    mapOf("firstName" to "Edited", "lastName" to "Local", "contactEmail" to "contact@example.test"),
                    owner,
                )
                .response
                .status,
        )
        assertEquals("Private", profiles.findByCompanyIdAndUserId(otherCompany.id!!, account.id!!)!!.firstName)
        assertEquals("Edited", profiles.findByCompanyIdAndUserId(company.id!!, account.id!!)!!.firstName)
        val customerSession = fixtures.customer(account)
        assertEquals(
            405,
            putJson(
                    "/api/users/me",
                    mapOf("firstName" to "Hijack", "lastName" to "Name", "email" to null),
                    customerSession,
                )
                .response
                .status,
        )
    }

    @Test
    fun `employee profile and access mutations cannot cross companies`() {
        val local = employee(company.id!!)
        val foreign = employee(otherCompany.id!!)
        assertEquals(
            404,
            putJson(
                    "/api/employees/${foreign.userId}",
                    mapOf("firstName" to "Changed", "lastName" to "Name", "loginEmail" to "changed@example.test"),
                    owner,
                )
                .response
                .status,
        )
        assertEquals(
            200,
            putJson(
                    "/api/employees/${local.userId}",
                    mapOf("firstName" to "Changed", "lastName" to "Name", "loginEmail" to "  UPDATED@EXAMPLE.TEST  "),
                    owner,
                )
                .response
                .status,
        )
        assertEquals("Local", accounts.findById(foreign.id!!).orElseThrow().firstName)
        val session = login(company.id!!, "updated@example.test")
        assertEquals(
            200,
            putJson("/api/employees/${local.userId}/enabled", mapOf("enabled" to false), owner).response.status,
        )
        assertEquals(401, me(session))
        assertEquals(
            401,
            postJson(
                    "/api/auth/login-staff",
                    mapOf("companyId" to company.id, "email" to "updated@example.test", "password" to "password123"),
                )
                .response
                .status,
        )
    }

    @Test
    fun `disable preserves ongoing and future visits and returns future warning`() {
        val employee = employee(company.id!!)
        val customer = customers.save(User(phoneNumber = "+48500100200"))
        val offering =
            offerings.save(
                pl.kacosmetology.scheduler.offering.Offering(
                    companyId = company.id!!,
                    name = "Visit",
                    durationMinutes = 30,
                    price = 100,
                ),
            )
        val start = java.time.LocalDateTime.ofInstant(now, ZoneId.of("Europe/Warsaw"))
        val future =
            bookings.save(
                pl.kacosmetology.scheduler.reservation.Reservation(
                    companyId = company.id!!,
                    customerId = customer.id!!,
                    employeeId = employee.userId,
                    serviceId = offering.id!!,
                    price = 100,
                    startTime = start.plusDays(1),
                    endTime = start.plusDays(1).plusMinutes(30),
                ),
            )
        bookings.save(
            pl.kacosmetology.scheduler.reservation.Reservation(
                companyId = company.id!!,
                customerId = customer.id!!,
                employeeId = employee.userId,
                serviceId = offering.id!!,
                price = 100,
                startTime = start.minusMinutes(10),
                endTime = start.plusMinutes(20),
            ),
        )
        val response = putJson("/api/employees/${employee.userId}/enabled", mapOf("enabled" to false), owner)
        assertEquals(200, response.response.status)
        val warnings = mapper.readTree(response.response.contentAsString)["affectedFutureBookings"]
        assertEquals(1, warnings.size())
        assertEquals(future.id, UUID.fromString(warnings[0]["reservationId"].asString()))
        assertEquals(2L, bookings.count())
        assertTrue(
            bookings.findAll().all { it.status == pl.kacosmetology.scheduler.reservation.ReservationStatus.BOOKED },
        )
    }

    @Test
    fun `concurrent setup token consumption succeeds once`() {
        val token = create()["passwordSetup"]["token"].asString()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val ready = java.util.concurrent.CountDownLatch(2)
        val go = java.util.concurrent.CountDownLatch(1)
        try {
            val attempts =
                (1..2).map {
                    pool.submit<Int> {
                        ready.countDown()
                        check(go.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        consume(token)
                    }
                }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            go.countDown()
            assertEquals(listOf(204, 401), attempts.map { it.get(15, java.util.concurrent.TimeUnit.SECONDS) }.sorted())
        } finally {
            go.countDown()
            pool.shutdownNow()
        }
    }

    private fun employee(companyId: UUID) =
        accounts.save(
            CompanyEmployee(
                companyId = companyId,
                userId = accounts.nextPublicId(),
                role = "EMPLOYEE",
                loginEmail = "employee@example.test",
                passwordHash = passwords.encode("password123"),
                firstName = "Local",
                lastName = "Staff",
            ),
        )

    private fun create() =
        mapper.readTree(
            postJson(
                    "/api/employees",
                    mapOf(
                        "firstName" to "Local",
                        "lastName" to "Employee",
                        "loginEmail" to "  EMPLOYEE@EXAMPLE.TEST  ",
                    ),
                    owner,
                )
                .also { assertEquals(201, it.response.status, it.response.contentAsString) }
                .response
                .contentAsString,
        )

    private fun consume(token: String, password: String = "password123") =
        postJson("/api/auth/staff-password", mapOf("token" to token, "newPassword" to password)).response.status

    private fun login(companyId: UUID, email: String, password: String = "password123"): Cookie =
        requireNotNull(
            postJson("/api/auth/login-staff", mapOf("companyId" to companyId, "email" to email, "password" to password))
                .also { assertEquals(200, it.response.status, it.response.contentAsString) }
                .response
                .getCookie("SESSION"),
        )

    private fun me(cookie: Cookie) = mvc.perform(get("/api/auth/me").cookie(cookie)).andReturn().response.status

    private fun postJson(path: String, body: Any?, cookie: Cookie? = null): MvcResult {
        val request = post(path).with(csrf())
        cookie?.let { request.cookie(it) }
        body?.let { request.contentType("application/json").content(mapper.writeValueAsString(it)) }
        return mvc.perform(request).andReturn()
    }

    private fun putJson(path: String, body: Any, cookie: Cookie): MvcResult =
        mvc.perform(
                put(path)
                    .with(csrf())
                    .cookie(cookie)
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(body)),
            )
            .andReturn()
}
