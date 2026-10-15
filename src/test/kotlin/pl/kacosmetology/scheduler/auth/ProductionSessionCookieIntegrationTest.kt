package pl.kacosmetology.scheduler.auth

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.session.data.redis.RedisIndexedSessionRepository
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

/** Verifies the prod profile forces Secure cookies even when the servlet connection is plain HTTP behind TLS. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Import(TestcontainersConfiguration::class)
class ProductionSessionCookieIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var sessions: RedisIndexedSessionRepository
    @Autowired private lateinit var environment: Environment
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var companies: CompanyRepository
    @Autowired private lateinit var employments: CompanyEmployeeRepository
    @Autowired private lateinit var passwords: PasswordEncoder
    @Autowired private lateinit var mapper: ObjectMapper
    @MockkBean private lateinit var smsSender: SmsSender
    @MockkBean private lateinit var s3Client: S3Client

    @Test
    fun `production session and CSRF cookies are Secure host only and session is HttpOnly`() {
        assertTrue(environment.activeProfiles.contains("prod"))
        val company = companies.save(Company(name = "Prod cookie"))
        val staff =
            users.save(
                User(
                    phoneNumber = "+48500119988",
                    firstName = "Prod",
                    lastName = "Cookie",
                    email = "cookie@salon.pl",
                    passwordHash = passwords.encode("password123"),
                ),
            )
        employments.save(CompanyEmployee(companyId = company.id!!, userId = staff.id!!, role = "OWNER"))
        val before = mvc.perform(get("/api/auth/csrf")).andReturn().response
        val csrfCookie = requireNotNull(before.getCookie("XSRF-TOKEN"))
        assertTrue(csrfCookie.secure)
        val response =
            mvc.perform(
                    post("/api/auth/login-staff")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", mapper.readTree(before.contentAsString)["token"].asString())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(mapOf("email" to staff.email, "password" to "password123"))),
                )
                .andReturn()
                .response
        assertEquals(200, response.status)
        val authenticated = requireNotNull(response.getCookie("SESSION"))
        assertTrue(authenticated.secure)
        assertTrue(authenticated.isHttpOnly)
        assertNull(authenticated.domain)
        val after = mvc.perform(get("/api/auth/csrf").cookie(authenticated)).andReturn().response
        val logout =
            mvc.perform(
                    post("/api/auth/logout")
                        .cookie(authenticated, requireNotNull(after.getCookie("XSRF-TOKEN")))
                        .header("X-XSRF-TOKEN", mapper.readTree(after.contentAsString)["token"].asString()),
                )
                .andReturn()
                .response
        assertEquals(204, logout.status)
        val expired = requireNotNull(logout.getCookie("SESSION"))
        assertTrue(expired.secure)
        assertTrue(expired.isHttpOnly)
        assertEquals("/", expired.path)
        assertEquals("Lax", expired.getAttribute("SameSite"))
        assertNull(expired.domain)
        assertTrue(requireNotNull(logout.getCookie("XSRF-TOKEN")).secure)
        assertEquals(0, expired.maxAge)
        assertNull(sessions.findById(String(java.util.Base64.getDecoder().decode(authenticated.value))))
    }
}
