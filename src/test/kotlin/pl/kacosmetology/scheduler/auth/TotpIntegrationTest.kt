package pl.kacosmetology.scheduler.auth

import com.ninjasquad.springmockk.MockkBean
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.clearMocks
import io.mockk.every
import jakarta.servlet.http.Cookie
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
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
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.company.*
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Full cookie/CSRF flows: no security post-processors or preauthenticated sessions. */
@SpringBootTest(properties = ["spring.session.data.redis.namespace=scheduler:totp-boundaries"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class TotpIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var accounts: CompanyEmployeeRepository
    @Autowired private lateinit var companies: CompanyRepository
    @Autowired private lateinit var passwords: PasswordEncoder
    @Autowired private lateinit var codes: TotpCodes
    @Autowired private lateinit var cipher: TotpCipher
    @Autowired private lateinit var recovery: StaffRecoveryCodeRepository
    @MockkSpyBean private lateinit var redis: StringRedisTemplate
    @MockkSpyBean private lateinit var grants: TotpGrantStore
    @Autowired private lateinit var recoveryAuthorizations: StaffRecoveryAuthorizationRepository
    @Autowired
    private lateinit var sessionRepository: org.springframework.session.data.redis.RedisIndexedSessionRepository
    @MockkBean private lateinit var clock: Clock
    @MockkBean private lateinit var sms: SmsSender
    @MockkBean private lateinit var s3: S3Client
    private val now = Instant.parse("2026-10-31T10:00:00Z")
    private val step
        get() = now.epochSecond / 30

    private lateinit var owner: CompanyEmployee
    private lateinit var employee: CompanyEmployee
    private lateinit var foreign: CompanyEmployee

    @BeforeEach
    fun setup() {
        every { clock.instant() } returns now
        every { clock.zone } returns ZoneOffset.UTC
        val salon = companies.saveAndFlush(Company(name = "Salon TOTP"))
        val other = companies.saveAndFlush(Company(name = "Other"))
        fun account(company: UUID, role: String, email: String) =
            accounts.saveAndFlush(
                CompanyEmployee(
                    companyId = company,
                    userId = UUID.randomUUID(),
                    role = role,
                    loginEmail = email,
                    passwordHash = passwords.encode("password123"),
                ),
            )
        owner = account(salon.id!!, "OWNER", "owner@example.test")
        employee = account(salon.id!!, "EMPLOYEE", "employee@example.test")
        foreign = account(other.id!!, "EMPLOYEE", "employee@example.test")
    }

    @Test
    fun `mandatory enrollment creates no staff access before confirmed code and only owner receives backups`() {
        for (account in listOf(owner, employee)) {
            val browser = Browser()
            val challenge = login(browser, account)
            assertEquals("TOTP_SETUP_REQUIRED", challenge["status"].asString())
            assertEquals(401, me(browser).response.status)
            assertEquals(401, mvc.perform(get("/api/employees").cookie(browser.session!!)).andReturn().response.status)
            val setup =
                ok(
                    post(
                        browser,
                        "/api/auth/totp/setup",
                        mapOf("challengeToken" to challenge["challengeToken"].asString()),
                    ),
                )
            assertEquals(32, setup["secret"].asString().length)
            assertTrue(setup["otpauthUri"].asString().startsWith("otpauth://totp/"))
            assertTrue(setup["otpauthUri"].asString().contains("issuer=Scheduler&algorithm=SHA1&digits=6&period=30"))
            if (account.role == "OWNER") assertEquals(10, setup["recoveryCodes"].size())
            else assertTrue(setup["recoveryCodes"].isNull)
            assertEquals(401, me(browser).response.status)
            val wrong =
                post(
                    browser,
                    "/api/auth/totp/setup/confirm",
                    mapOf("setupToken" to setup["setupToken"].asString(), "code" to wrong(setup["secret"].asString())),
                )
            assertEquals(401, wrong.response.status)
            assertNull(accounts.findById(account.id!!).orElseThrow().totpSecret)
            confirm(browser, setup)
            assertEquals(200, me(browser).response.status)
            val stored = accounts.findById(account.id!!).orElseThrow()
            assertNotEquals(setup["secret"].asString(), stored.totpSecret)
            assertEquals(setup["secret"].asString(), cipher.decrypt(stored.totpSecret!!, account.id.toString()))
        }
        assertEquals(10, recovery.count())
    }

    @Test
    fun `all password entrypoints including company selection require TOTP before authorization`() {
        foreign.loginEmail = owner.loginEmail
        accounts.saveAndFlush(foreign)
        val browser = Browser()
        val result =
            ok(
                post(
                    browser,
                    "/api/auth/login-staff-email",
                    mapOf("email" to owner.loginEmail, "password" to "password123"),
                ),
            )
        assertEquals("COMPANY_SELECTION_REQUIRED", result["status"].asString())
        val selected =
            ok(
                post(
                    browser,
                    "/api/auth/complete-staff-login",
                    mapOf("selectionToken" to result["selectionToken"].asString(), "companyId" to owner.companyId),
                ),
            )
        assertEquals("TOTP_SETUP_REQUIRED", selected["status"].asString())
        assertEquals(401, me(browser).response.status)
        val setup =
            ok(post(browser, "/api/auth/totp/setup", mapOf("challengeToken" to selected["challengeToken"].asString())))
        confirm(browser, setup)
        assertEquals(owner.id.toString(), ok(me(browser))["employmentId"].asString())
        val one = Browser()
        val single =
            ok(
                post(
                    one,
                    "/api/auth/login-staff-email",
                    mapOf("email" to employee.loginEmail, "password" to "password123"),
                ),
            )
        assertEquals("TOTP_SETUP_REQUIRED", single["status"].asString())
        assertEquals(401, me(one).response.status)
    }

    @Test
    fun `TOTP replay is rejected across grants and concurrent correct verification authenticates once`() {
        val enrolled = enroll(employee)
        val browser = Browser()
        val challenge = login(browser, employee)
        val token = challenge["challengeToken"].asString()
        val request = mapOf("challengeToken" to token, "code" to codes.at(enrolled["secret"].asString(), step))
        refresh(browser)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                List(2) {
                    pool.submit<Int> {
                        start.await()
                        post(browser.copy(), "/api/auth/totp/verify", request).response.status
                    }
                }
            start.countDown()
            assertEquals(listOf(200, 401), results.map { it.get(15, TimeUnit.SECONDS) }.sorted())
        } finally {
            pool.shutdownNow()
        }
        val another = Browser()
        val next = login(another, employee)
        assertEquals(
            401,
            post(another, "/api/auth/totp/verify", request + ("challengeToken" to next["challengeToken"].asString()))
                .response
                .status,
        )
        assertEquals(401, me(another).response.status)
        assertEquals(
            200,
            post(
                    another,
                    "/api/auth/totp/verify",
                    mapOf(
                        "challengeToken" to next["challengeToken"].asString(),
                        "code" to codes.at(enrolled["secret"].asString(), step + 1),
                    ),
                )
                .response
                .status,
        )
    }

    @Test
    fun `owner backup is single use starts recovery and replaces credentials only after confirmation`() {
        val oldBrowser = Browser()
        val original = enroll(owner, oldBrowser)
        val browser = Browser()
        val challenge = login(browser, owner)
        val code = original["recoveryCodes"][0].asString()
        val result =
            ok(
                post(
                    browser,
                    "/api/auth/totp/recover",
                    mapOf("challengeToken" to challenge["challengeToken"].asString(), "recoveryCode" to code),
                ),
            )
        assertEquals("TOTP_SETUP_REQUIRED", result["status"].asString())
        assertEquals(9, recovery.count())
        assertEquals(401, me(browser).response.status)
        assertEquals(200, me(oldBrowser).response.status)
        val replayBrowser = Browser()
        val replay = login(replayBrowser, owner)
        assertEquals(
            401,
            post(
                    replayBrowser,
                    "/api/auth/totp/recover",
                    mapOf("challengeToken" to replay["challengeToken"].asString(), "recoveryCode" to code),
                )
                .response
                .status,
        )
        val setup =
            ok(post(browser, "/api/auth/totp/setup", mapOf("challengeToken" to result["challengeToken"].asString())))
        assertNotEquals(original["secret"].asString(), setup["secret"].asString())
        confirm(browser, setup)
        assertEquals(200, me(browser).response.status)
        assertEquals(401, me(oldBrowser).response.status)
        assertEquals(10, recovery.count())
        assertFalse(
            recovery.existsById(
                RecoveryCodeId(owner.id, TotpTransactions.recoveryHash(original["recoveryCodes"][1].asString())),
            ),
        )
        assertEquals(
            401,
            post(
                    replayBrowser,
                    "/api/auth/totp/verify",
                    mapOf(
                        "challengeToken" to replay["challengeToken"].asString(),
                        "code" to codes.at(original["secret"].asString(), step),
                    ),
                )
                .response
                .status,
        )
    }

    @Test
    fun `last recovery code can resume after lost recovery and failed setup responses using the original challenge`() {
        val original = enroll(owner)
        val code = original["recoveryCodes"][0].asString()
        recovery.deleteAll(recovery.findAll().filter { it.codeHash != TotpTransactions.recoveryHash(code) })
        val browser = Browser()
        val token = login(browser, owner)["challengeToken"].asString()
        val body = mapOf("challengeToken" to token)
        // A password-only challenge cannot start replacement enrollment.
        assertEquals(401, post(browser, "/api/auth/totp/setup", body).response.status)
        val result = ok(post(browser, "/api/auth/totp/recover", body + ("recoveryCode" to code)))
        assertEquals(token, result["challengeToken"].asString())
        assertEquals(0, recovery.count())
        assertEquals(1, recoveryAuthorizations.count())
        // Discard the response: the old challenge now carries durable recovery authorization.
        assertEquals(401, post(Browser(), "/api/auth/totp/setup", body).response.status)
        val other = Browser()
        val otherToken = login(other, owner)["challengeToken"].asString()
        assertEquals(401, post(other, "/api/auth/totp/setup", mapOf("challengeToken" to otherToken)).response.status)
        assertEquals(
            401,
            post(other, "/api/auth/totp/recover", mapOf("challengeToken" to otherToken, "recoveryCode" to code))
                .response
                .status,
        )
        // Resuming the same authorization does not inspect or spend a recovery code again.
        ok(post(browser, "/api/auth/totp/recover", body + ("recoveryCode" to "no-longer-needed")))
        every { grants.issue(any()) } throws RedisConnectionFailureException("simulated grant write failure")
        try {
            assertEquals(500, post(browser, "/api/auth/totp/setup", body).response.status)
        } finally {
            clearMocks(grants, answers = true)
        }
        val lostSetup = ok(post(browser, "/api/auth/totp/setup", body))
        val setup = ok(post(browser, "/api/auth/totp/setup", body))
        assertNotEquals(lostSetup["secret"].asString(), setup["secret"].asString())
        assertEquals(0, recovery.count())
        confirm(browser, setup)
        assertEquals(200, me(browser).response.status)
        assertEquals(10, recovery.count())
        assertEquals(0, recoveryAuthorizations.count())
        assertEquals(401, post(other, "/api/auth/totp/setup", body).response.status)
    }

    @Test
    fun `Redis allowance release failure after commit preserves confirmation verification and reset success`() {
        every { redis.execute(any<RedisScript<Long>>(), any<List<String>>()) } throws
            RedisConnectionFailureException("simulated post-commit cleanup failure")
        try {
            val ownerBrowser = Browser()
            val ownerSetup = enroll(owner, ownerBrowser)
            val employeeSetup = enroll(employee)
            val recoveryBrowser = Browser()
            val recoveryToken = login(recoveryBrowser, owner)["challengeToken"].asString()
            val recovered =
                ok(
                    post(
                        recoveryBrowser,
                        "/api/auth/totp/recover",
                        mapOf(
                            "challengeToken" to recoveryToken,
                            "recoveryCode" to ownerSetup["recoveryCodes"][0].asString(),
                        ),
                    ),
                )
            assertEquals("TOTP_SETUP_REQUIRED", recovered["status"].asString())
            assertEquals(9, recovery.count())
            assertEquals(1, accounts.findById(employee.id!!).orElseThrow().authVersion)
            val browser = Browser()
            val challenge = login(browser, employee)
            ok(
                post(
                    browser,
                    "/api/auth/totp/verify",
                    mapOf(
                        "challengeToken" to challenge["challengeToken"].asString(),
                        "code" to codes.at(employeeSetup["secret"].asString(), step),
                    ),
                ),
            )
            assertEquals(step, accounts.findById(employee.id!!).orElseThrow().totpLastStep)
            assertEquals(200, me(browser).response.status)
            assertEquals(
                204,
                post(
                        ownerBrowser,
                        "/api/employees/${employee.userId}/totp/reset",
                        mapOf(
                            "currentPassword" to "password123",
                            "code" to codes.at(ownerSetup["secret"].asString(), step),
                        ),
                        true,
                    )
                    .response
                    .status,
            )
            val reset = accounts.findById(employee.id!!).orElseThrow()
            assertNull(reset.totpSecret)
            assertEquals(2, reset.authVersion)
            assertEquals(401, me(browser).response.status)
        } finally {
            clearMocks(redis, answers = true)
        }
    }

    @Test
    fun `employees cannot recover with backup codes and owner reset is scoped reauthenticated and revokes access`() {
        val ownerBrowser = Browser()
        val ownerSetup = enroll(owner, ownerBrowser)
        val employeeBrowser = Browser()
        enroll(employee, employeeBrowser)
        val pendingBrowser = Browser()
        val pending = login(pendingBrowser, employee)
        assertEquals(
            401,
            post(
                    pendingBrowser,
                    "/api/auth/totp/recover",
                    mapOf(
                        "challengeToken" to pending["challengeToken"].asString(),
                        "recoveryCode" to ownerSetup["recoveryCodes"][0].asString(),
                    ),
                )
                .response
                .status,
        )
        assertEquals(10, recovery.count())
        val resetBody =
            mapOf("currentPassword" to "password123", "code" to codes.at(ownerSetup["secret"].asString(), step))
        assertEquals(
            403,
            post(employeeBrowser, "/api/employees/${employee.userId}/totp/reset", resetBody, true).response.status,
        )
        assertEquals(
            404,
            post(ownerBrowser, "/api/employees/${foreign.userId}/totp/reset", resetBody, true).response.status,
        )
        assertEquals(
            401,
            post(
                    ownerBrowser,
                    "/api/employees/${employee.userId}/totp/reset",
                    resetBody + ("currentPassword" to "wrong"),
                    true,
                )
                .response
                .status,
        )
        assertEquals(200, me(ownerBrowser).response.status)
        assertEquals(
            204,
            post(ownerBrowser, "/api/employees/${employee.userId}/totp/reset", resetBody, true).response.status,
        )
        assertEquals(401, me(employeeBrowser).response.status)
        assertEquals(
            401,
            post(
                    pendingBrowser,
                    "/api/auth/totp/verify",
                    mapOf("challengeToken" to pending["challengeToken"].asString(), "code" to "123456"),
                )
                .response
                .status,
        )
        assertEquals("TOTP_SETUP_REQUIRED", login(Browser(), employee)["status"].asString())
        assertEquals(200, me(ownerBrowser).response.status)
    }

    @Test
    fun `grants are browser bound expire after ten minutes and disappear on success`() {
        val browser = Browser()
        val challenge = login(browser, employee)
        val body = mapOf("challengeToken" to challenge["challengeToken"].asString())
        assertEquals(401, post(Browser(), "/api/auth/totp/setup", body).response.status)
        every { clock.instant() } returns now.plusSeconds(600)
        val expired = post(browser, "/api/auth/totp/setup", body)
        assertEquals(401, expired.response.status)
        assertEquals("TOTP_CHALLENGE_EXPIRED", mapper.readTree(expired.response.contentAsString)["code"].asString())
    }

    @Test
    fun `CSRF expected context validation and verification limits protect the new endpoints`() {
        for (path in listOf("/setup", "/verify", "/setup/confirm", "/recover")) {
            assertEquals(
                403,
                mvc.perform(post("/api/auth/totp$path").contentType("application/json").content("{}"))
                    .andReturn()
                    .response
                    .status,
            )
        }
        val browser = Browser()
        val setup = enroll(employee)
        val challenge = login(browser, employee)
        val body =
            mapOf(
                "challengeToken" to challenge["challengeToken"].asString(),
                "code" to wrong(setup["secret"].asString()),
            )
        repeat(5) { assertEquals(401, post(browser, "/api/auth/totp/verify", body).response.status) }
        val limited = post(browser, "/api/auth/totp/verify", body)
        assertEquals(429, limited.response.status)
        assertNotNull(limited.response.getHeader("Retry-After"))
        val ownerBrowser = Browser()
        val ownerSetup = enroll(owner, ownerBrowser)
        val reset = mapOf("currentPassword" to "password123", "code" to codes.at(ownerSetup["secret"].asString(), step))
        assertEquals(400, post(ownerBrowser, "/api/employees/${employee.userId}/totp/reset", reset).response.status)
        assertEquals(200, me(ownerBrowser).response.status)
    }

    @Test
    fun `partially recreated Redis session is discarded without authenticating or throwing`() {
        val id = UUID.randomUUID().toString()
        val key = "scheduler:totp-boundaries:sessions:$id"
        sessionRepository.sessionRedisOperations.opsForHash<String, Any>().put(key, "lastAccessedTime", 0L)
        val cookie = Cookie("SESSION", java.util.Base64.getEncoder().encodeToString(id.toByteArray()))
        assertEquals(401, me(Browser(session = cookie)).response.status)
        assertFalse(redis.hasKey(key))
    }

    @Test
    fun `pending TOTP grants cannot survive logout all or durable credential changes`() {
        val ownerBrowser = Browser()
        val setup = enroll(owner, ownerBrowser)
        val pending = Browser()
        val challenge = login(pending, owner)
        assertEquals(204, post(ownerBrowser, "/api/auth/logout-all", emptyMap<String, String>(), true).response.status)
        val revoked =
            post(
                pending,
                "/api/auth/totp/verify",
                mapOf(
                    "challengeToken" to challenge["challengeToken"].asString(),
                    "code" to codes.at(setup["secret"].asString(), step),
                ),
            )
        assertEquals(401, revoked.response.status)
        val browser = Browser()
        val current = login(browser, employee)
        val account = accounts.findById(employee.id!!).orElseThrow()
        account.authVersion++
        accounts.saveAndFlush(account)
        assertEquals(
            401,
            post(browser, "/api/auth/totp/setup", mapOf("challengeToken" to current["challengeToken"].asString()))
                .response
                .status,
        )
    }

    @Test
    fun `challenge secrets are encrypted in Redis and purpose cannot be changed`() {
        val browser = Browser()
        val challenge = login(browser, owner)
        val token = challenge["challengeToken"].asString()
        assertEquals(
            401,
            post(browser, "/api/auth/totp/verify", mapOf("challengeToken" to token, "code" to "123456"))
                .response
                .status,
        )
        val setup = ok(post(browser, "/api/auth/totp/setup", mapOf("challengeToken" to token)))
        val key = "scheduler:totp-grant:" + StaffLoginSelectionStore.hash(setup["setupToken"].asString())
        val value = requireNotNull(redis.opsForValue().get(key))
        assertFalse(value.contains(setup["secret"].asString()))
        assertFalse(value.contains(setup["recoveryCodes"][0].asString()))
        assertFalse(value.contains("owner@example.test"))
        assertTrue(redis.getExpire(key) in 1..600)
        assertEquals(401, post(browser, "/api/auth/totp/setup", mapOf("challengeToken" to token)).response.status)
        confirm(browser, setup)
        assertFalse(redis.hasKey(key))
    }

    @Test
    fun `IP admission counts invalid grants and account failure budget spans new password grants`() {
        val setup = enroll(employee)
        val invalidCode = wrong(setup["secret"].asString())
        repeat(2) {
            val browser = Browser()
            val challenge = login(browser, employee)
            repeat(5) {
                assertEquals(
                    401,
                    post(
                            browser,
                            "/api/auth/totp/verify",
                            mapOf("challengeToken" to challenge["challengeToken"].asString(), "code" to invalidCode),
                        )
                        .response
                        .status,
                )
            }
        }
        val browser = Browser()
        val challenge = login(browser, employee)
        assertEquals(
            429,
            post(
                    browser,
                    "/api/auth/totp/verify",
                    mapOf(
                        "challengeToken" to challenge["challengeToken"].asString(),
                        "code" to codes.at(setup["secret"].asString(), step),
                    ),
                )
                .response
                .status,
        )
        redis.delete("scheduler:totp-limit:ip:" + StaffLoginSelectionStore.hash("127.0.0.1"))
        repeat(30) {
            assertEquals(
                401,
                post(Browser(), "/api/auth/totp/setup", mapOf("challengeToken" to "a".repeat(43))).response.status,
            )
        }
        assertEquals(
            429,
            post(Browser(), "/api/auth/totp/setup", mapOf("challengeToken" to "a".repeat(43))).response.status,
        )
    }

    private data class Browser(var session: Cookie? = null, var csrf: Cookie? = null, var token: String = "")

    private fun refresh(browser: Browser) {
        val request = get("/api/auth/csrf")
        browser.session?.let { request.cookie(it) }
        browser.csrf?.let { request.cookie(it) }
        val result = mvc.perform(request).andReturn()
        browser.csrf = result.response.getCookie("XSRF-TOKEN") ?: browser.csrf
        browser.token = ok(result)["token"].asString()
    }

    private fun post(browser: Browser, path: String, body: Any, context: Boolean = false): MvcResult {
        refresh(browser)
        val request = post(path).cookie(browser.csrf!!).header("X-XSRF-TOKEN", browser.token)
        browser.session?.let { request.cookie(it) }
        if (context) {
            val identity = ok(me(browser))
            request.header(
                "X-Expected-Session-Context",
                "v1:${identity["userId"].asString()}:${identity["companyId"].asString()}:${identity["employmentId"].asString()}:${identity["authorities"][0].asString()}",
            )
        }
        val result =
            mvc.perform(request.contentType("application/json").content(mapper.writeValueAsString(body))).andReturn()
        result.response.getCookie("SESSION")?.let { if (it.maxAge != 0) browser.session = it }
        result.response.getCookie("XSRF-TOKEN")?.let { browser.csrf = if (it.maxAge == 0) null else it }
        return result
    }

    private fun login(browser: Browser, account: CompanyEmployee) =
        ok(
            post(
                browser,
                "/api/auth/login-staff",
                mapOf("companyId" to account.companyId, "email" to account.loginEmail, "password" to "password123"),
            ),
        )

    private fun enroll(account: CompanyEmployee, browser: Browser = Browser()): JsonNode {
        val challenge = login(browser, account)
        val setup =
            ok(post(browser, "/api/auth/totp/setup", mapOf("challengeToken" to challenge["challengeToken"].asString())))
        confirm(browser, setup)
        return setup
    }

    private fun confirm(browser: Browser, setup: JsonNode) {
        val result =
            post(
                browser,
                "/api/auth/totp/setup/confirm",
                mapOf(
                    "setupToken" to setup["setupToken"].asString(),
                    "code" to codes.at(setup["secret"].asString(), step - 1),
                ),
            )
        assertEquals("AUTHENTICATED", ok(result)["status"].asString())
        assertTrue(result.response.getHeader("Cache-Control")!!.contains("no-store"))
    }

    private fun me(browser: Browser): MvcResult {
        val request = get("/api/auth/me")
        browser.session?.let { request.cookie(it) }
        return mvc.perform(request).andReturn()
    }

    private fun ok(result: MvcResult): JsonNode {
        assertEquals(200, result.response.status, result.response.contentAsString)
        return mapper.readTree(result.response.contentAsString)
    }

    private fun wrong(secret: String): String =
        (0..999999).asSequence().map { it.toString().padStart(6, '0') }.first { codes.match(secret, it) == null }
}
