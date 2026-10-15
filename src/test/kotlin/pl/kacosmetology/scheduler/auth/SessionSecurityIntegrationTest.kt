package pl.kacosmetology.scheduler.auth

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import jakarta.servlet.http.Cookie
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
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
    @Autowired private lateinit var passwords: PasswordEncoder
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var securityFilter: FilterChainProxy
    @MockkBean private lateinit var smsSender: SmsSender
    @MockkBean private lateinit var s3Client: S3Client
    @MockkBean private lateinit var clock: Clock
    @Autowired private lateinit var accountSessions: AccountSessionRegistry
    private lateinit var loginTime: Instant

    private lateinit var staff: User
    private lateinit var owner: CompanyEmployee
    private lateinit var employee: CompanyEmployee
    private val password = "securePassword1"

    @BeforeEach
    fun setup() {
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
        owner = employments.save(CompanyEmployee(companyId = a.id!!, userId = staff.id!!, role = "OWNER"))
        employee = employments.save(CompanyEmployee(companyId = b.id!!, userId = staff.id!!, role = "EMPLOYEE"))
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
        assertEquals(owner.companyId, me["companyId"].asLong())
        assertEquals(owner.id, me["employmentId"].asLong())
        assertEquals("ROLE_OWNER", me["authorities"][0].asString())
        assertTrue(browser.session!!.isHttpOnly)
        assertEquals("/", browser.session.path)
        assertEquals("Lax", browser.session.getAttribute("SameSite"))
        assertNull(browser.session.domain)
        assertFalse(browser.session.secure)
    }

    @Test
    fun `selection response does not create an authenticated or anonymous session`() {
        val browser = csrf()
        val result = post(browser, "/api/auth/login-staff", credentials())
        assertEquals(200, result.response.status)
        val body = mapper.readTree(result.response.contentAsString)
        assertEquals("EMPLOYMENT_SELECTION_REQUIRED", body["status"].asString())
        assertEquals(owner.id, body["employments"][0]["employmentId"].asLong())
        assertEquals(employee.id, body["employments"][1]["employmentId"].asLong())
        assertNull(result.response.getCookie("SESSION"))
        assertNull(result.request.getSession(false))
        assertEquals(401, mvc.perform(get("/api/auth/me")).andReturn().response.status)
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
        val second = customer(staff.phoneNumber)
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
        users.deleteById(me(browser)["userId"].asLong())
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
        assertEquals(employee.id, me(browser)["employmentId"].asLong())
        assertEquals("ROLE_EMPLOYEE", me(browser)["authorities"][0].asString())
    }

    @Test
    fun `foreign employment selection cannot create session`() {
        val other = users.save(User(phoneNumber = "+48500444444", firstName = "Other", lastName = "Staff"))
        val foreign =
            employments.save(CompanyEmployee(companyId = owner.companyId, userId = other.id!!, role = "OWNER"))
        val result = post(csrf(), "/api/auth/login-staff", credentials(foreign.id!!))
        assertEquals(400, result.response.status)
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
        assertEquals(employee.companyId, me(browser)["companyId"].asLong())
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
    fun `real CSRF allows profile mutation and profile changes are loaded from database`() {
        val browser = login(employee.id!!)
        val result =
            mvc.perform(
                    put("/api/users/me")
                        .cookie(browser.session!!, browser.csrf)
                        .header("X-XSRF-TOKEN", browser.token)
                        .contentType("application/json")
                        .content(
                            mapper.writeValueAsString(
                                mapOf("firstName" to "Updated", "lastName" to "Session", "email" to staff.email),
                            ),
                        ),
                )
                .andReturn()
        assertEquals(200, result.response.status)
        val profile = mvc.perform(get("/api/users/me").cookie(browser.session)).andReturn().response
        assertEquals("Updated", mapper.readTree(profile.contentAsString)["firstName"].asString())
        assertEquals(employee.id, me(browser)["employmentId"].asLong())
    }

    @Test
    fun `recycled phone number cannot bind an old account session to a new user`() {
        val browser = customer("+48500888888")
        val userId = me(browser)["userId"].asLong()
        jdbc.update("update users set phone_number = ? where id = ?", "+48500888889", userId)
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
        assertEquals(owner.id, mapper.readTree(result.response.contentAsString)["employmentId"].asLong())
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

    /** A formerly accepted signed credential and Redis entry; production no longer understands this protocol. */
    private fun legacyBearerFixture(): String {
        val sessionId = java.util.UUID.randomUUID().toString()
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

    private data class Browser(val session: Cookie?, val csrf: Cookie, val token: String)

    private fun id(cookie: Cookie) = String(Base64.getDecoder().decode(cookie.value))

    private fun credentials(employmentId: Long? = null) =
        mapOf("email" to staff.email, "password" to password, "employmentId" to employmentId)

    private fun csrf(session: Cookie? = null): Browser {
        val builder = get("/api/auth/csrf")
        if (session != null) builder.cookie(session)
        val result = mvc.perform(builder).andReturn()
        assertEquals(200, result.response.status)
        val cookie = requireNotNull(result.response.getCookie("XSRF-TOKEN"))
        val body = mapper.readTree(result.response.contentAsString)
        assertEquals("X-XSRF-TOKEN", body["headerName"].asString())
        assertEquals(cookie.value, body["token"].asString())
        return Browser(session, cookie, body["token"].asString())
    }

    private fun post(browser: Browser, path: String, body: Any? = null): MvcResult {
        val builder = post(path).cookie(browser.csrf).header("X-XSRF-TOKEN", browser.token)
        browser.session?.let { builder.cookie(it) }
        if (body != null) builder.contentType("application/json").content(mapper.writeValueAsString(body))
        return mvc.perform(builder).andReturn()
    }

    private fun login(employmentId: Long, original: Cookie? = null): Browser {
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
