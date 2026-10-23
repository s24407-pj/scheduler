package pl.kacosmetology.scheduler.auth

import com.ninjasquad.springmockk.MockkBean
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import jakarta.servlet.http.Cookie
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.FilterChainProxy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
import org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME
import org.springframework.session.data.redis.RedisIndexedSessionRepository
import org.springframework.session.web.http.SessionRepositoryFilter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.context.WebApplicationContext
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.AccountSessionRegistry
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.ExpectedSessionContextFilter
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

/** Exercises real session cookies, Redis persistence and real SPA CSRF tokens without security post-processors. */
@SpringBootTest(properties = ["spring.session.data.redis.namespace=scheduler:session-boundaries"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class SessionSecurityIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var sessions: RedisIndexedSessionRepository
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var companies: CompanyRepository
    @Autowired private lateinit var employments: CompanyEmployeeRepository
    @Autowired private lateinit var reservations: ReservationRepository
    @MockkSpyBean private lateinit var passwords: PasswordEncoder
    @Autowired private lateinit var authService: AuthService
    @Autowired
    private lateinit var sessionAuthentication: pl.kacosmetology.scheduler.security.SessionAuthenticationService
    @Autowired private lateinit var schedules: pl.kacosmetology.scheduler.workschedule.WorkScheduleService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var securityFilter: FilterChainProxy
    @MockkBean private lateinit var smsSender: SmsSender
    @MockkBean private lateinit var s3Client: S3Client
    @MockkBean private lateinit var clock: Clock
    @Autowired private lateinit var accountSessions: AccountSessionRegistry
    @Autowired private lateinit var testSessions: pl.kacosmetology.scheduler.security.TestSessions
    private lateinit var loginTime: Instant

    private lateinit var staff: User
    private lateinit var owner: CompanyEmployee
    private lateinit var employee: CompanyEmployee
    private val password = "securePassword1"

    @BeforeEach
    fun setup() {
        clearMocks(passwords)
        grantBrowsers.clear()
        loginTime = Instant.now()
        every { clock.instant() } returns loginTime
        every { clock.zone } returns ZoneId.of("Europe/Warsaw")
        redis.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
        reservations.deleteAll()
        employments.deleteAll()
        users.deleteAll()
        companies.deleteAll()
        every { smsSender.sendOtp(any(), any()) } just Runs
        staff =
            users.save(
                User(
                    phoneNumber = "+48500123456",
                    firstName = "Staff",
                    lastName = "Session",
                    email = "sessions@salon.pl",
                    passwordHash = passwords.encode(password),
                ),
            )
        val a = companies.save(Company(name = "Alpha"))
        val b = companies.save(Company(name = "Beta"))
        owner =
            employments.save(
                CompanyEmployee(
                    companyId = a.id!!,
                    userId = staff.id!!,
                    role = "OWNER",
                    loginEmail = staff.email,
                    passwordHash = staff.passwordHash,
                    firstName = "Alpha",
                    lastName = "Owner",
                ),
            )
        employee =
            employments.save(
                CompanyEmployee(
                    companyId = b.id!!,
                    userId = staff.id!!,
                    role = "EMPLOYEE",
                    loginEmail = staff.email,
                    passwordHash = staff.passwordHash,
                    firstName = "Beta",
                    lastName = "Employee",
                ),
            )
    }

    @Test
    fun `customer OTP authenticates Redis session and never returns credential in JSON`() {
        val browser = customer("+48500999888")
        val me = me(browser)
        assertEquals("ROLE_CUSTOMER", me["authorities"][0].asString())
        assertTrue(me["companyId"].isNull)
        assertTrue(me["employmentId"].isNull)
        assertEquals(200, mvc.perform(get("/api/users/me").cookie(browser.session!!)).andReturn().response.status)
        assertNotNull(sessions.findById(id(browser.session)))
    }

    @Test
    fun `staff session is scoped to exactly one employment and cookie is HttpOnly host only Lax`() {
        val browser = login(owner.id!!)
        val me = me(browser)
        assertEquals(owner.companyId, UUID.fromString(me["companyId"].asString()))
        assertEquals(owner.id, UUID.fromString(me["employmentId"].asString()))
        assertEquals("ROLE_OWNER", me["authorities"][0].asString())
        assertTrue(browser.session!!.isHttpOnly)
        assertEquals("/", browser.session.path)
        assertEquals("Lax", browser.session.getAttribute("SameSite"))
        assertNull(browser.session.domain)
        assertFalse(browser.session.secure)
    }

    @Test
    fun `staff login requires explicit company and creates no session when company is missing`() {
        val result = post(csrf(), "/api/auth/login-staff", mapOf("email" to staff.email, "password" to password))
        assertEquals(400, result.response.status)
        assertNull(result.response.getCookie("SESSION"))
    }

    @Test
    fun `no session and legacy bearer credentials cannot authenticate and API never redirects`() {
        for (builder in
            listOf(
                get("/api/auth/me"),
                get("/api/company/settings").header("Authorization", "Bearer ${legacyBearerFixture()}"),
            )) {
            val response = mvc.perform(builder).andReturn().response
            assertEquals(401, response.status)
            assertNull(response.getHeader("Location"))
            assertEquals("UNAUTHORIZED", mapper.readTree(response.contentAsString)["code"].asString())
        }
    }

    @Test
    fun `unsafe authenticated calls require valid CSRF and logout expires cookies and is idempotent`() {
        val browser = login(owner.id!!)
        assertEquals(403, mvc.perform(post("/api/auth/logout").cookie(browser.session!!)).andReturn().response.status)
        assertEquals(
            403,
            mvc.perform(post("/api/auth/logout").cookie(browser.session, browser.csrf).header("X-XSRF-TOKEN", "wrong"))
                .andReturn()
                .response
                .status,
        )
        assertEquals(200, meStatus(browser))
        val result = post(browser, "/api/auth/logout")
        assertEquals(204, result.response.status)
        assertEquals(0, result.response.getCookie("SESSION")!!.maxAge)
        assertEquals(0, result.response.getCookie("XSRF-TOKEN")!!.maxAge)
        assertNull(sessions.findById(id(browser.session)))
        assertEquals(401, meStatus(browser))
        assertEquals(204, post(csrf(), "/api/auth/logout").response.status)
    }

    @Test
    fun `public auth endpoints also require CSRF and refresh works after login and logout`() {
        for (path in listOf("request-code", "verify-code", "login-staff")) {
            assertEquals(
                403,
                mvc.perform(post("/api/auth/$path").contentType("application/json").content("{}"))
                    .andReturn()
                    .response
                    .status,
            )
        }
        val before = csrf()
        val result = post(before, "/api/auth/login-staff", credentials(owner.id!!))
        assertEquals(200, result.response.status)
        assertEquals(0, result.response.getCookie("XSRF-TOKEN")!!.maxAge)
        val after = csrf(result.response.getCookie("SESSION"))
        assertNotEquals(before.token, after.token)
        assertEquals(204, post(after, "/api/auth/logout").response.status)
        val loggedOut = csrf()
        assertNotEquals(after.token, loggedOut.token)
        assertEquals(200, post(loggedOut, "/api/auth/login-staff", credentials(owner.id!!)).response.status)
    }

    @Test
    fun `staff writes require a well formed expected scope before any business side effect`() {
        val browser = login(owner.id!!)
        for (header in listOf(null, "", "invalid", browser.expectedContext!!.uppercase())) {
            val result =
                post(browser.copy(expectedContext = header), "/api/offering-categories", mapOf("name" to "Stary szkic"))
            assertEquals(400, result.response.status, result.response.contentAsString)
            assertEquals(
                "SESSION_CONTEXT_REQUIRED",
                mapper.readTree(result.response.contentAsString)["code"].asString(),
            )
        }
        val duplicate =
            mvc.perform(
                    post("/api/offering-categories")
                        .cookie(browser.session!!, browser.csrf)
                        .header("X-XSRF-TOKEN", browser.token)
                        .header(ExpectedSessionContextFilter.HEADER, browser.expectedContext, browser.expectedContext)
                        .contentType("application/json")
                        .content("{\"name\":\"Stary szkic\"}"),
                )
                .andReturn()
        assertEquals(400, duplicate.response.status)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM offering_categories", Int::class.java))
        val saved = post(browser, "/api/offering-categories", mapOf("name" to "Aktualny szkic"))
        assertEquals(201, saved.response.status, saved.response.contentAsString)
    }

    @Test
    fun `a different account cookie cannot submit the former salon form even with rotated CSRF`() {
        val original = login(owner.id!!)
        assertEquals(204, post(original, "/api/auth/logout").response.status)
        jdbc.update("UPDATE company_employees SET role = 'OWNER' WHERE id = ?", employee.id)
        val current = login(employee.id!!)
        val staleForm = current.copy(expectedContext = original.expectedContext, token = original.token)
        val result = post(staleForm, "/api/offering-categories", mapOf("name" to "Nie ten salon"))
        assertEquals(409, result.response.status, result.response.contentAsString)
        assertEquals("SESSION_CONTEXT_CHANGED", mapper.readTree(result.response.contentAsString)["code"].asString())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM offering_categories", Int::class.java))
        assertEquals(200, meStatus(current))
        assertEquals(201, post(current, "/api/offering-categories", mapOf("name" to "Salon beta")).response.status)
    }

    @Test
    fun `each expected identity field is checked and valid scope still requires CSRF`() {
        val browser = login(owner.id!!)
        val fields = browser.expectedContext!!.split(":")
        for (index in 1..4) {
            val changed = fields.toMutableList()
            changed[index] = if (index == 4) "ROLE_EMPLOYEE" else UUID.randomUUID().toString()
            val result =
                post(
                    browser.copy(expectedContext = changed.joinToString(":")),
                    "/api/offering-categories",
                    mapOf("name" to "Nie zapisuj"),
                )
            assertEquals(409, result.response.status, "field $index: ${result.response.contentAsString}")
            assertEquals("SESSION_CONTEXT_CHANGED", mapper.readTree(result.response.contentAsString)["code"].asString())
        }
        assertEquals(
            403,
            post(browser.copy(token = "wrong"), "/api/offering-categories", mapOf("name" to "Nie zapisuj"))
                .response
                .status,
        )
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM offering_categories", Int::class.java))
    }

    @Test
    fun `expired cookie remains unauthorized and relogin to same scope accepts the previous context`() {
        val original = login(owner.id!!)
        assertEquals(204, post(original, "/api/auth/logout").response.status)
        val expired = csrf().copy(session = original.session, expectedContext = original.expectedContext)
        assertEquals(401, post(expired, "/api/offering-categories", mapOf("name" to "Nie zapisuj")).response.status)
        val current = login(owner.id!!).copy(expectedContext = original.expectedContext)
        assertEquals(201, post(current, "/api/offering-categories", mapOf("name" to "To samo konto")).response.status)
    }

    @Test
    fun `staff logout all isolates its company account from customer and other employment sessions`() {
        val first = login(owner.id!!)
        val secondDevice = login(owner.id!!)
        val otherEmployment = login(employee.id!!)
        val customer = customer(staff.phoneNumber)
        val otherCustomer = customer("+48500777777")
        assertEquals(2, sessions.findByPrincipalName("staff:${owner.id}:0").size)
        assertEquals(204, post(first, "/api/auth/logout-all").response.status)
        for (browser in listOf(first, secondDevice)) assertRevoked(browser)
        for (browser in listOf(otherEmployment, customer, otherCustomer)) assertEquals(200, meStatus(browser))
        assertTrue(sessions.findByPrincipalName("staff:${owner.id}:0").isEmpty())
    }

    @Test
    fun `customer logout all isolates customer account from staff and other customers`() {
        val first = customer(staff.phoneNumber)
        val second = csrf(testSessions.customer(staff))
        val staffBrowser = login(owner.id!!)
        val otherCustomer = customer("+48500777777")
        assertEquals(204, post(first, "/api/auth/logout-all").response.status)
        assertRevoked(first)
        assertRevoked(second)
        assertEquals(200, meStatus(staffBrowser))
        assertEquals(200, meStatus(otherCustomer))
    }

    @Test
    fun `customer activity cannot extend absolute deadline and exact 24 hours expires`() {
        val browser = customer("+48500777777")
        val sessionId = id(browser.session!!)
        val original = sessions.findById(sessionId)!!
        val deadline = original.getAttribute<Instant>(AccountSessionRegistry.CUSTOMER_DEADLINE_ATTRIBUTE)
        assertEquals(loginTime.plus(Duration.ofHours(24)), deadline)
        every { clock.instant() } returns loginTime.plus(Duration.ofHours(23))
        assertEquals(200, meStatus(browser))
        assertEquals(
            deadline,
            sessions.findById(sessionId)!!.getAttribute<Instant>(AccountSessionRegistry.CUSTOMER_DEADLINE_ATTRIBUTE),
        )
        every { clock.instant() } returns deadline!!.minusNanos(1)
        assertEquals(200, meStatus(browser))
        every { clock.instant() } returns deadline
        assertRevoked(browser)
    }

    @Test
    fun `staff activity refreshes idle expiry beyond 24 hours from login`() {
        val browser = login(owner.id!!)
        val sessionId = id(browser.session!!)
        val session = sessions.findById(sessionId)!!
        assertEquals(Duration.ofHours(24), session.maxInactiveInterval)
        assertNull(session.getAttribute<Instant>(AccountSessionRegistry.CUSTOMER_DEADLINE_ATTRIBUTE))
        val lastActivity = Instant.now().minus(Duration.ofHours(23))
        session.lastAccessedTime = lastActivity
        sessions.save(session)
        every { clock.instant() } returns loginTime.plus(Duration.ofHours(48))
        assertEquals(200, meStatus(browser))
        assertTrue(sessions.findById(sessionId)!!.lastAccessedTime.isAfter(lastActivity))
        val expired = sessions.findById(sessionId)!!
        expired.lastAccessedTime = Instant.now().minus(Duration.ofHours(24)).minusSeconds(1)
        sessions.save(expired)
        assertRevoked(browser)
    }

    @Test
    fun `login saved after logout all is rejected while new generation stays indexed`() {
        val browser = login(owner.id!!)
        val context =
            sessions.findById(id(browser.session!!))!!.getAttribute<SecurityContext>(SPRING_SECURITY_CONTEXT_KEY)!!
        val principal = context.authentication!!.principal as CustomUserDetails
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending =
                executor.submit<Cookie> {
                    val delayed = sessions.createSession()
                    accountSessions.sessionAttributes(principal).forEach { (name, value) ->
                        delayed.setAttribute(name, value)
                    }
                    delayed.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context)
                    captured.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    sessions.save(delayed)
                    Cookie("SESSION", Base64.getEncoder().encodeToString(delayed.id.toByteArray()))
                }
            assertTrue(captured.await(10, TimeUnit.SECONDS))
            assertEquals(204, post(browser, "/api/auth/logout-all").response.status)
            val fresh = login(owner.id!!)
            release.countDown()
            val delayed = pending.get(10, TimeUnit.SECONDS)
            assertEquals(401, mvc.perform(get("/api/auth/me").cookie(delayed)).andReturn().response.status)
            assertEquals(200, meStatus(fresh))
            assertEquals(setOf(id(fresh.session!!)), sessions.findByPrincipalName("staff:${owner.id}:1").keys)
            // Every login that survives the revocation remains tracked for the next logout-all.
            assertEquals(204, post(fresh, "/api/auth/logout-all").response.status)
            assertRevoked(fresh)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `authentication rotates a preexisting anonymous session and invalidates its old ID`() {
        val anonymous = sessions.createSession()
        anonymous.setAttribute("existing", "kept")
        sessions.save(anonymous)
        val original = Cookie("SESSION", Base64.getEncoder().encodeToString(anonymous.id.toByteArray()))
        val browser = login(owner.id!!, original)
        assertNotEquals(original.value, browser.session!!.value)
        assertNull(sessions.findById(anonymous.id))
        assertEquals("kept", sessions.findById(id(browser.session))!!.getAttribute<String>("existing"))
        assertEquals(200, meStatus(browser))
    }

    @Test
    fun `expired sessions are rejected and default inactivity interval is 24 hours`() {
        val browser = login(owner.id!!)
        val session = sessions.findById(id(browser.session!!))!!
        assertEquals(Duration.ofHours(24), session.maxInactiveInterval)
        session.maxInactiveInterval = Duration.ofSeconds(1)
        session.lastAccessedTime = Instant.now().minusSeconds(10)
        sessions.save(session)
        assertNull(sessions.findById(session.id))
        assertEquals(401, meStatus(browser))
    }

    @Test
    fun `deleted customer account is rejected and its session is invalidated`() {
        val browser = customer("+48500222222")
        users.deleteById(UUID.fromString(me(browser)["userId"].asString()))
        assertEquals(401, meStatus(browser))
        assertNull(sessions.findById(id(browser.session!!)))
    }

    @Test
    fun `removed employment invalidates the session immediately`() {
        val browser = login(owner.id!!)
        employments.deleteById(owner.id!!)
        assertRevoked(browser)
    }

    @Test
    fun `changed role cannot preserve old owner privileges or silently update scope`() {
        val browser = login(owner.id!!)
        jdbc.update("update company_employees set role = 'EMPLOYEE' where id = ?", owner.id)
        assertRevoked(browser)
    }

    @Test
    fun `changed employment company invalidates session`() {
        val browser = login(owner.id!!)
        val other = companies.save(Company(name = "Moved"))
        jdbc.update("update company_employees set company_id = ? where id = ?", other.id, owner.id)
        assertRevoked(browser)
    }

    @Test
    fun `employment reassigned to another user invalidates session`() {
        val browser = login(owner.id!!)
        val other = users.save(User(phoneNumber = "+48500333333", firstName = "Other", lastName = "User"))
        jdbc.update("update company_employees set user_id = ? where id = ?", other.id, owner.id)
        assertRevoked(browser)
    }

    @Test
    fun `deleted staff account invalidates session`() {
        val browser = login(owner.id!!)
        employments.deleteAll()
        users.deleteById(staff.id!!)
        assertRevoked(browser)
    }

    @Test
    fun `authenticated browser cannot switch employment through login request input`() {
        val browser = login(employee.id!!)
        assertEquals(409, post(browser, "/api/auth/login-staff", credentials(owner.id!!)).response.status)
        assertEquals(employee.id, UUID.fromString(me(browser)["employmentId"].asString()))
        assertEquals("ROLE_EMPLOYEE", me(browser)["authorities"][0].asString())
    }

    @Test
    fun `foreign employment selection cannot create session`() {
        val other = users.save(User(phoneNumber = "+48500444444", firstName = "Other", lastName = "Staff"))
        val foreign =
            employments.save(CompanyEmployee(companyId = owner.companyId, userId = other.id!!, role = "OWNER"))
        val result =
            post(
                csrf(),
                "/api/auth/login-staff",
                mapOf("email" to "foreign@salon.pl", "password" to password, "companyId" to foreign.companyId),
            )
        assertEquals(401, result.response.status)
        assertNull(result.response.getCookie("SESSION"))
    }

    @Test
    fun `unrelated session attributes and request scope input cannot grant owner authority`() {
        val browser = login(employee.id!!)
        val session = sessions.findById(id(browser.session!!))!!
        session.setAttribute("companyId", owner.companyId)
        session.setAttribute("employmentId", owner.id!!)
        session.setAttribute("role", "ROLE_OWNER")
        sessions.save(session)
        val result =
            post(
                browser,
                "/api/offerings",
                mapOf("companyId" to owner.companyId, "name" to "Test", "price" to 50, "durationMinutes" to 30),
            )
        assertEquals(400, result.response.status)
        val validRequest =
            post(browser, "/api/offerings", mapOf("name" to "Test", "price" to 50, "durationMinutes" to 30))
        assertEquals(403, validRequest.response.status)
        assertEquals(employee.companyId, UUID.fromString(me(browser)["companyId"].asString()))
    }

    @Test
    fun `tampered authenticated scope and mismatched authorities are rejected`() {
        val browser = login(employee.id!!)
        val session = sessions.findById(id(browser.session!!))!!
        val security = session.getAttribute<SecurityContext>(SPRING_SECURITY_CONTEXT_KEY)!!
        val principal = requireNotNull(security.authentication).principal as CustomUserDetails
        security.authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                principal.copy(companyId = owner.companyId),
                null,
                principal.authorities,
            )
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, security)
        sessions.save(session)
        assertRevoked(browser)
    }

    @Test
    fun `mismatched authentication authorities invalidate an otherwise valid principal`() {
        val browser = login(employee.id!!)
        val session = sessions.findById(id(browser.session!!))!!
        val security = session.getAttribute<SecurityContext>(SPRING_SECURITY_CONTEXT_KEY)!!
        val principal = requireNotNull(security.authentication).principal as CustomUserDetails
        security.authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OWNER")),
            )
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, security)
        sessions.save(session)
        assertRevoked(browser)
    }

    @Test
    fun `staff profile reads company local presentation and customer profile update route is removed`() {
        val browser = login(employee.id!!)
        val profile = mvc.perform(get("/api/staff/me").cookie(browser.session!!)).andReturn().response
        assertEquals(200, profile.status)
        assertEquals("Beta", mapper.readTree(profile.contentAsString)["firstName"].asString())
        assertEquals(403, mvc.perform(get("/api/users/me").cookie(browser.session)).andReturn().response.status)
    }

    @Test
    fun `recycled phone number cannot bind an old account session to a new user`() {
        val browser = customer("+48500888888")
        val userId = UUID.fromString(me(browser)["userId"].asString())
        jdbc.update("update customer_accounts set phone_number = ? where id = ?", "+48500888889", userId)
        users.save(User(phoneNumber = "+48500888888", firstName = "New", lastName = "Identity"))
        assertRevoked(browser)
    }

    @Test
    fun `Redis context contains only scalar identity and no entity or password hash`() {
        val browser = login(owner.id!!)
        val session = sessions.findById(id(browser.session!!))!!
        val security = session.getAttribute<SecurityContext>(SPRING_SECURITY_CONTEXT_KEY)!!
        val auth = requireNotNull(security.authentication)
        assertNull(auth.credentials)
        assertNull(auth.details)
        val principal = auth.principal as CustomUserDetails
        assertEquals("", principal.password)
        assertEquals("staff:${owner.id}:0", session.getAttribute<String>(PRINCIPAL_NAME_INDEX_NAME))
        val bytes =
            ByteArrayOutputStream()
                .also { out -> ObjectOutputStream(out).use { it.writeObject(security) } }
                .toByteArray()
        val serialized = String(bytes, Charsets.ISO_8859_1)
        assertFalse(serialized.contains(staff.passwordHash!!))
        assertFalse(serialized.contains("pl.kacosmetology.scheduler.user.User"))
        assertFalse(serialized.contains("hibernate"))
        assertTrue(redis.keys("scheduler:session-boundaries:sessions:*").isNotEmpty())
    }

    @Test
    fun `independent servlet request pipeline restores Redis authentication without local HttpSession`() {
        val browser = login(owner.id!!)
        val anotherRepository =
            RedisIndexedSessionRepository(sessions.sessionRedisOperations).also {
                it.setRedisKeyNamespace("scheduler:session-boundaries")
                it.setDefaultMaxInactiveInterval(Duration.ofHours(24))
            }
        val anotherFilter = SessionRepositoryFilter(anotherRepository)
        val anotherPipeline =
            MockMvcBuilders.webAppContextSetup(context)
                .addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(
                    anotherFilter,
                    securityFilter,
                )
                .build()
        val result = anotherPipeline.perform(get("/api/auth/me").cookie(browser.session!!)).andReturn()
        assertEquals(200, result.response.status)
        assertEquals(
            owner.id,
            UUID.fromString(mapper.readTree(result.response.contentAsString)["employmentId"].asString()),
        )
        sessions.deleteById(id(browser.session))
        assertEquals(
            401,
            anotherPipeline.perform(get("/api/auth/me").cookie(browser.session)).andReturn().response.status,
        )
    }

    @Test
    fun `credentialed CORS preflight allows explicit frontend and CSRF header but rejects unrelated origins`() {
        val good =
            mvc.perform(
                    options("/api/auth/login-staff")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"),
                )
                .andReturn()
                .response
        assertEquals(200, good.status)
        assertEquals("http://localhost:5173", good.getHeader("Access-Control-Allow-Origin"))
        assertEquals("true", good.getHeader("Access-Control-Allow-Credentials"))
        assertTrue(good.getHeader("Access-Control-Allow-Headers")!!.lowercase().contains("x-xsrf-token"))
        val bad =
            mvc.perform(
                    options("/api/auth/me")
                        .header("Origin", "https://unrelated.example")
                        .header("Access-Control-Request-Method", "GET"),
                )
                .andReturn()
                .response
        assertEquals(403, bad.status)
        assertNull(bad.getHeader("Access-Control-Allow-Origin"))
    }

    @Test
    fun `email first checks each company password and authenticates only the single match`() {
        employee.passwordHash = passwords.encode("differentPassword1")
        employments.saveAndFlush(employee)
        val result = emailLogin()
        assertEquals(200, result.response.status)
        assertEquals("AUTHENTICATED", mapper.readTree(result.response.contentAsString)["status"].asString())
        val browser = csrf(requireNotNull(result.response.getCookie("SESSION")))
        val identity = me(browser)
        assertEquals(owner.companyId.toString(), identity["companyId"].asString())
        assertEquals(owner.id.toString(), identity["employmentId"].asString())
        assertEquals("ROLE_OWNER", identity["authorities"][0].asString())
    }

    @Test
    fun `selection lists only password verified companies and establishes exact employee scope`() {
        val excluded = companies.save(Company(name = "Unverified"))
        employments.saveAndFlush(
            CompanyEmployee(
                companyId = excluded.id!!,
                userId = UUID.randomUUID(),
                role = "EMPLOYEE",
                loginEmail = staff.email,
                passwordHash = passwords.encode("differentPassword1"),
            ),
        )
        val result = emailLogin()
        assertEquals(200, result.response.status)
        assertNotNull(result.response.getCookie("SESSION"))
        val body = mapper.readTree(result.response.contentAsString)
        assertEquals("COMPANY_SELECTION_REQUIRED", body["status"].asString())
        assertEquals(
            setOf(owner.companyId.toString(), employee.companyId.toString()),
            (0 until body["companies"].size()).map { body["companies"][it]["companyId"].asString() }.toSet(),
        )
        assertEquals(
            setOf("Alpha", "Beta"),
            (0 until body["companies"].size()).map { body["companies"][it]["name"].asString() }.toSet(),
        )
        assertFalse(result.response.contentAsString.contains("authVersion"))
        assertFalse(result.response.contentAsString.contains(owner.id.toString()))
        val token = body["selectionToken"].asString()
        val key = "scheduler:staff-login-selection:${StaffLoginSelectionStore.hash(token)}"
        assertTrue(redis.getExpire(key) in 1..120)
        val grant = requireNotNull(redis.opsForValue().get(key))
        assertFalse(grant.contains(password))
        assertFalse(grant.contains(requireNotNull(owner.passwordHash)))
        val selected = completeSelection(token, employee.companyId)
        assertEquals(200, selected.response.status)
        val browser = csrf(requireNotNull(selected.response.getCookie("SESSION")))
        val identity = me(browser)
        assertEquals(employee.companyId.toString(), identity["companyId"].asString())
        assertEquals(employee.id.toString(), identity["employmentId"].asString())
        assertEquals("ROLE_EMPLOYEE", identity["authorities"][0].asString())
        assertEquals(401, completeSelection(token, owner.companyId).response.status)
        assertNull(redis.opsForValue().get(key))
    }

    @Test
    fun `unknown email wrong password disabled and passwordless accounts have identical public errors`() {
        val wrong = emailLogin(password = "incorrectPassword").response
        val unknown = emailLogin(email = "nobody@salon.pl").response
        employee.enabled = false
        owner.passwordHash = null
        employments.saveAllAndFlush(listOf(employee, owner))
        val disabled = emailLogin().response
        listOf(wrong, unknown, disabled).forEach {
            assertEquals(401, it.status)
            assertNull(it.getCookie("SESSION"))
            val body = mapper.readTree(it.contentAsString)
            assertEquals("INVALID_CREDENTIALS", body["code"].asString())
            assertEquals("Nieprawidłowe dane logowania", body["detail"].asString())
            assertFalse(body.has("companies"))
        }
    }

    @Test
    fun `expired invalid and mismatched selections never authenticate and invalid choice burns token`() {
        var token = selectionToken()
        redis.expire("scheduler:staff-login-selection:${StaffLoginSelectionStore.hash(token)}", Duration.ZERO)
        assertEquals(401, completeSelection(token, owner.companyId).response.status)
        token = selectionToken()
        val wrong = completeSelection(token, UUID.randomUUID())
        assertEquals(401, wrong.response.status)
        assertNull(wrong.response.getCookie("SESSION"))
        assertEquals(401, completeSelection(token, owner.companyId).response.status)
        assertEquals(401, completeSelection("a".repeat(43), owner.companyId).response.status)
        val legacy = selectionToken()
        redis
            .opsForValue()
            .set(
                "scheduler:staff-login-selection:${StaffLoginSelectionStore.hash(legacy)}",
                "[]",
                Duration.ofSeconds(120),
            )
        assertEquals(401, completeSelection(legacy, owner.companyId).response.status)
    }

    @Test
    fun `concurrent selection consumes grant once in the same browser`() {
        val token = selectionToken()
        val browsers = List(2) { csrf(grantBrowsers.getValue(token).session) }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = browsers.map { browser ->
                executor.submit<Int> {
                    start.await()
                    post(
                            browser,
                            "/api/auth/complete-staff-login",
                            mapOf("selectionToken" to token, "companyId" to owner.companyId),
                        )
                        .response
                        .status
                }
            }
            start.countDown()
            assertEquals(listOf(200, 401), futures.map { it.get(10, TimeUnit.SECONDS) }.sorted())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `password reset disable role or generation change invalidates a pending grant`() {
        val mutations =
            listOf(
                "UPDATE company_employees SET auth_version = auth_version + 1 WHERE id = ?",
                "UPDATE company_employees SET enabled = false WHERE id = ?",
                "UPDATE company_employees SET role = 'EMPLOYEE' WHERE id = ?",
            )
        mutations.forEach { sql ->
            val token = selectionToken()
            jdbc.update(sql, owner.id)
            val result = completeSelection(token, owner.companyId)
            assertEquals(401, result.response.status)
            assertNull(result.response.getCookie("SESSION"))
            jdbc.update("UPDATE company_employees SET enabled = true, role = 'OWNER' WHERE id = ?", owner.id)
        }
        val token = selectionToken()
        accountSessions.revokeAccount("staff:${owner.id}")
        assertEquals(401, completeSelection(token, owner.companyId).response.status)
    }

    @Test
    fun `email limiter is normalized shared across IPs and counted once per logical attempt`() {
        repeat(10) { index ->
            val browser = csrf()
            val result =
                mvc.perform(
                        post("/api/auth/login-staff-email")
                            .cookie(browser.csrf)
                            .header("X-XSRF-TOKEN", browser.token)
                            .with {
                                it.remoteAddr = "192.0.2.$index"
                                it
                            }
                            .contentType("application/json")
                            .content(
                                mapper.writeValueAsString(
                                    mapOf("email" to "  SESSIONS@SALON.PL  ", "password" to "incorrectPassword"),
                                ),
                            ),
                    )
                    .andReturn()
            assertEquals(401, result.response.status)
        }
        assertEquals(
            "10",
            redis.opsForValue().get("rate:login:email:${StaffLoginSelectionStore.hash("sessions@salon.pl")}"),
        )
        val limited = emailLogin().response
        assertEquals(429, limited.status)
        assertNotNull(limited.getHeader("Retry-After"))
    }

    @Test
    fun `candidate overflow fails generically while scoped fallback remains available`() {
        repeat(AuthService.MAX_EMAIL_CANDIDATES - 1) { index ->
            val company = companies.save(Company(name = "Extra $index"))
            employments.saveAndFlush(
                CompanyEmployee(
                    companyId = company.id!!,
                    userId = UUID.randomUUID(),
                    role = "EMPLOYEE",
                    loginEmail = staff.email,
                    passwordHash = owner.passwordHash,
                ),
            )
        }
        val failure = emailLogin().response
        assertEquals(401, failure.status)
        assertFalse(mapper.readTree(failure.contentAsString).has("companies"))
        assertEquals(owner.companyId.toString(), me(login(owner.id!!))["companyId"].asString())
    }

    @Test
    fun `new endpoints require CSRF reject authenticated browsers and rotate anonymous session`() {
        listOf("/api/auth/login-staff-email", "/api/auth/complete-staff-login").forEach { path ->
            assertEquals(
                403,
                mvc.perform(post(path).contentType("application/json").content("{}")).andReturn().response.status,
            )
        }
        val authenticated = login(owner.id!!)
        assertEquals(409, post(authenticated, "/api/auth/login-staff-email", emailCredentials()).response.status)
        assertEquals(
            409,
            post(
                    authenticated,
                    "/api/auth/complete-staff-login",
                    mapOf("selectionToken" to "a".repeat(43), "companyId" to employee.companyId),
                )
                .response
                .status,
        )
        val anonymous = sessions.createSession().also { sessions.save(it) }
        val oldCookie = Cookie("SESSION", Base64.getEncoder().encodeToString(anonymous.id.toByteArray()))
        val prelogin = csrf(oldCookie)
        val grant = post(prelogin, "/api/auth/login-staff-email", emailCredentials())
        assertEquals(200, grant.response.status)
        assertEquals(401, mvc.perform(get("/api/auth/me").cookie(oldCookie)).andReturn().response.status)
        val token = mapper.readTree(grant.response.contentAsString)["selectionToken"].asString()
        val result =
            post(
                prelogin,
                "/api/auth/complete-staff-login",
                mapOf("selectionToken" to token, "companyId" to owner.companyId),
            )
        assertEquals(200, result.response.status)
        val newCookie = requireNotNull(result.response.getCookie("SESSION"))
        assertNotEquals(oldCookie.value, newCookie.value)
        assertNull(sessions.findById(anonymous.id))
        assertEquals(0, result.response.getCookie("XSRF-TOKEN")!!.maxAge)
        val refreshed = csrf(newCookie)
        assertNotEquals(prelogin.token, refreshed.token)
        assertEquals(204, post(refreshed, "/api/auth/logout").response.status)
    }

    @Test
    fun `revocation between committed verification and session establishment rejects authentication`() {
        employee.enabled = false
        employments.saveAndFlush(employee)
        val result =
            requireNotNull(
                authService
                    .loginStaffEmail(
                        pl.kacosmetology.scheduler.auth.dto.StaffEmailLoginRequest("sessions@salon.pl", password),
                        "127.0.0.1",
                        { "browser" },
                    )
                    .authentication,
            )
        accountSessions.revokeAccount("staff:${owner.id}")
        val request = org.springframework.mock.web.MockHttpServletRequest()
        org.junit.jupiter.api.assertThrows<org.springframework.security.authentication.BadCredentialsException> {
            sessionAuthentication.authenticate(
                result.principal,
                request,
                org.springframework.mock.web.MockHttpServletResponse(),
                result.sessionAttributes,
            )
        }
        assertNull(request.getSession(false))
    }

    @Test
    fun `selection is bound to original browser and CSRF refresh keeps its grant`() {
        val token = selectionToken()
        val original = csrf(grantBrowsers.getValue(token).session)
        assertEquals(
            200,
            post(
                    original,
                    "/api/auth/complete-staff-login",
                    mapOf("selectionToken" to token, "companyId" to owner.companyId),
                )
                .response
                .status,
        )
        val stolen = selectionToken()
        val response =
            post(
                    csrf(),
                    "/api/auth/complete-staff-login",
                    mapOf("selectionToken" to stolen, "companyId" to owner.companyId),
                )
                .response
        assertEquals(401, response.status)
        assertNull(response.getCookie("SESSION"))
        assertEquals(401, completeSelection(stolen, owner.companyId).response.status)
    }

    @Test
    fun `final password allowance can complete and completion abuse is independently bounded`() {
        redis.opsForValue().set("rate:login:127.0.0.1", "9", Duration.ofMinutes(1))
        val token = selectionToken()
        assertEquals("10", redis.opsForValue().get("rate:login:127.0.0.1"))
        assertEquals(200, completeSelection(token, owner.companyId).response.status)
        repeat(9) { assertEquals(401, completeSelection("x".repeat(43), owner.companyId).response.status) }
        val limited = completeSelection("x".repeat(43), owner.companyId).response
        assertEquals(429, limited.status)
        assertNotNull(limited.getHeader("Retry-After"))
        assertEquals(429, emailLogin().response.status)
    }

    @Test
    fun `BCrypt holds no transaction or employee write lock while schedule operation commits`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        every { passwords.matches(any(), any()) } answers
            {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                if (first.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
                callOriginal()
            }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val pending = executor.submit<MvcResult> { emailLogin() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            executor
                .submit {
                    schedules.setSchedule(
                        owner.companyId,
                        owner.userId,
                        pl.kacosmetology.scheduler.workschedule.dto.SetWeeklyScheduleRequest(emptyList()),
                    )
                }
                .get(5, TimeUnit.SECONDS)
            release.countDown()
            assertEquals(200, pending.get(15, TimeUnit.SECONDS).response.status)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `credential or generation changes during BCrypt reject the entire attempt`() {
        val mutations =
            listOf(
                "UPDATE company_employees SET password_hash = 'changed' WHERE id = ?",
                "UPDATE company_employees SET enabled = false WHERE id = ?",
                "UPDATE company_employees SET role = 'EMPLOYEE' WHERE id = ?",
                "UPDATE company_employees SET login_email = 'changed@salon.pl' WHERE id = ?",
                "UPDATE company_employees SET auth_version = auth_version + 1 WHERE id = ?",
            )
        (mutations + "revoke").forEach { mutation ->
            val first = AtomicBoolean(true)
            every { passwords.matches(any(), any()) } answers
                {
                    if (first.compareAndSet(true, false)) {
                        if (mutation == "revoke") accountSessions.revokeAccount("staff:${owner.id}")
                        else jdbc.update(mutation, owner.id)
                    }
                    callOriginal()
                }
            val result = emailLogin().response
            assertEquals(401, result.status, mutation)
            assertNull(result.getCookie("SESSION"))
            jdbc.update(
                "UPDATE company_employees SET password_hash = ?, enabled = true, role = 'OWNER', login_email = ? WHERE id = ?",
                owner.passwordHash,
                staff.email,
                owner.id,
            )
        }
    }

    private fun emailCredentials(email: String = "sessions@salon.pl", password: String = this.password) =
        mapOf("email" to email, "password" to password)

    private val grantBrowsers = mutableMapOf<String, Browser>()

    private fun emailLogin(email: String = "sessions@salon.pl", password: String = this.password): MvcResult {
        val browser = csrf()
        val result = post(browser, "/api/auth/login-staff-email", emailCredentials(email, password))
        if (result.response.status == 200) {
            val body = mapper.readTree(result.response.contentAsString)
            if (body.has("selectionToken") && !body["selectionToken"].isNull) {
                grantBrowsers[body["selectionToken"].asString()] =
                    browser.copy(session = result.response.getCookie("SESSION") ?: browser.session)
            }
        }
        return result
    }

    private fun selectionToken(): String {
        val result = emailLogin()
        assertEquals(200, result.response.status)
        return mapper.readTree(result.response.contentAsString)["selectionToken"].asString()
    }

    private fun completeSelection(token: String, companyId: UUID) =
        post(
            grantBrowsers[token] ?: csrf(),
            "/api/auth/complete-staff-login",
            mapOf("selectionToken" to token, "companyId" to companyId),
        )

    /** A formerly accepted signed credential and Redis entry; production no longer understands this protocol. */
    private fun legacyBearerFixture(): String {
        val sessionId = UUID.randomUUID().toString()
        redis.opsForValue().set("session:$sessionId", staff.id.toString(), Duration.ofHours(1))
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString(mapper.writeValueAsBytes(mapOf("alg" to "HS256", "typ" to "JWT")))
        val claims =
            encoder.encodeToString(
                mapper.writeValueAsBytes(
                    mapOf(
                        "sub" to staff.phoneNumber,
                        "jti" to sessionId,
                        "role" to "owner",
                        "companyId" to owner.companyId,
                        "employmentId" to owner.id,
                        "iat" to Instant.now().epochSecond,
                        "exp" to Instant.now().plusSeconds(3600).epochSecond,
                    ),
                ),
            )
        // Historical public development key, only for rejection of the retired protocol.
        val key = "ToJestTymczasowySekretDoRozwojuAplikacjiLokalnieWymienGoNaProdukcji".toByteArray()
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        val signature = encoder.encodeToString(mac.doFinal("$header.$claims".toByteArray()))
        return "$header.$claims.$signature"
    }

    private data class Browser(
        val session: Cookie?,
        val csrf: Cookie,
        val token: String,
        val expectedContext: String? = null,
    )

    private fun id(cookie: Cookie) = String(Base64.getDecoder().decode(cookie.value))

    private fun credentials(employmentId: UUID? = null) =
        mapOf(
            "email" to staff.email,
            "password" to password,
            "companyId" to if (employmentId == employee.id) employee.companyId else owner.companyId,
        )

    private fun csrf(session: Cookie? = null): Browser {
        val builder = get("/api/auth/csrf")
        if (session != null) builder.cookie(session)
        val result = mvc.perform(builder).andReturn()
        assertEquals(200, result.response.status)
        val cookie = requireNotNull(result.response.getCookie("XSRF-TOKEN"))
        val body = mapper.readTree(result.response.contentAsString)
        assertEquals("X-XSRF-TOKEN", body["headerName"].asString())
        assertEquals(cookie.value, body["token"].asString())
        val principal =
            session
                ?.let { sessions.findById(id(it)) }
                ?.getAttribute<SecurityContext>(SPRING_SECURITY_CONTEXT_KEY)
                ?.authentication
                ?.principal as? CustomUserDetails
        return Browser(
            session,
            cookie,
            body["token"].asString(),
            principal?.takeIf { it.companyId != null }?.let(ExpectedSessionContextFilter::context),
        )
    }

    private fun post(browser: Browser, path: String, body: Any? = null): MvcResult {
        val builder = post(path).cookie(browser.csrf).header("X-XSRF-TOKEN", browser.token)
        browser.session?.let { builder.cookie(it) }
        browser.expectedContext?.let { builder.header(ExpectedSessionContextFilter.HEADER, it) }
        if (body != null) builder.contentType("application/json").content(mapper.writeValueAsString(body))
        return mvc.perform(builder).andReturn()
    }

    private fun login(employmentId: UUID, original: Cookie? = null): Browser {
        val result = post(csrf(original), "/api/auth/login-staff", credentials(employmentId))
        assertEquals(200, result.response.status)
        assertFalse(mapper.readTree(result.response.contentAsString).has("token"))
        return csrf(requireNotNull(result.response.getCookie("SESSION")))
    }

    private fun customer(phone: String): Browser {
        val anonymous = csrf()
        assertEquals(200, post(anonymous, "/api/auth/request-code", mapOf("phoneNumber" to phone)).response.status)
        val code = requireNotNull(redis.opsForValue().get("otp:$phone"))
        val result =
            post(
                anonymous,
                "/api/auth/verify-code",
                mapOf("phoneNumber" to phone, "code" to code, "firstName" to "Customer", "lastName" to "Session"),
            )
        assertEquals(200, result.response.status)
        assertFalse(mapper.readTree(result.response.contentAsString).has("token"))
        return csrf(requireNotNull(result.response.getCookie("SESSION")))
    }

    private fun meStatus(browser: Browser) =
        mvc.perform(get("/api/auth/me").cookie(browser.session!!)).andReturn().response.status

    private fun me(browser: Browser) =
        mapper.readTree(
            mvc.perform(get("/api/auth/me").cookie(browser.session!!))
                .andReturn()
                .also { assertEquals(200, it.response.status) }
                .response
                .contentAsString,
        )

    private fun assertRevoked(browser: Browser) {
        assertEquals(401, meStatus(browser))
        assertNull(sessions.findById(id(browser.session!!)))
    }
}
