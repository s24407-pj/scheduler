package pl.kacosmetology.scheduler.auth

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import jakarta.servlet.http.Cookie
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginRequest
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.TestSessions
import pl.kacosmetology.scheduler.testId
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AuthFlowIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var redisTemplate: StringRedisTemplate

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var companyRepository: CompanyRepository

    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired private lateinit var testSessions: TestSessions

    @Autowired private lateinit var reservationRepository: ReservationRepository

    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    // Zastępujemy prawdziwy serwis sztucznym (żeby nie sypało logami ani nie wysyłało prawdziwych SMS)
    @MockkBean private lateinit var smsSender: SmsSender

    @MockkBean private lateinit var s3Client: S3Client

    @BeforeEach
    fun setup() {
        // Czyścimy Redis i bazę przed każdym testem
        redisTemplate.connectionFactory?.connection?.serverCommands()?.flushAll()
        reservationRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        every { smsSender.sendOtp(any(), any()) } just Runs
    }

    @Test
    fun `full customer registration and login via SMS should create session`() {
        val phoneNumber = "+48111222333"

        // 1. KROK 1: Klient prosi o kod SMS
        val requestDto = RequestCodeRequest(phoneNumber = phoneNumber)

        mockMvc
            .post("/api/auth/request-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(requestDto)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.message") { exists() }
            }

        // 2. Odczytujemy kod z Redis (symulacja odczytania SMS)
        val otpCode = redisTemplate.opsForValue().get("otp:$phoneNumber")
        Assertions.assertNotNull(otpCode, "Kod OTP powinien zostac zapisany w Redis")

        // 3. KROK 2: Klient wysyła kod do weryfikacji wraz z danymi (bo to jego pierwszy raz)
        val verifyDto =
            VerifyCodeRequest(
                phoneNumber = phoneNumber,
                code = otpCode!!,
                firstName = "Jan",
                lastName = "Kowalski",
            )

        mockMvc
            .post("/api/auth/verify-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(verifyDto)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.token") { doesNotExist() }
                cookie { exists("SESSION") }
            }

        // 4. Upewniamy się, że użytkownik został stworzony w bazie i zużyty kod zniknął
        val createdUser = userRepository.findByPhoneNumber(phoneNumber)
        Assertions.assertNotNull(createdUser, "Uzytkownik powinien zostac utworzony")
        assertEquals(phoneNumber, createdUser?.phoneNumber)

        val codeAfterVerification = redisTemplate.opsForValue().get("otp:$phoneNumber")
        Assertions.assertNull(codeAfterVerification, "Zużyty kod powinien zostac usuniety z Redis")
    }

    @Test
    fun `OTP verification needs no names and normalizes phone before identity lookup`() {
        mockMvc
            .post("/api/auth/request-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = """{"phoneNumber":" 48111222444 "}"""
            }
            .andExpect { status { isOk() } }
        val phone = "+48111222444"
        val code = requireNotNull(redisTemplate.opsForValue().get("otp:$phone"))
        mockMvc
            .post("/api/auth/verify-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = """{"phoneNumber":"48111222444","code":"$code"}"""
            }
            .andExpect {
                status { isOk() }
                cookie { exists("SESSION") }
            }
        val account = requireNotNull(userRepository.findByPhoneNumber(phone))
        assertEquals(phone, account.phoneNumber)
        Assertions.assertNull(redisTemplate.opsForValue().get("otp:$phone"))
    }

    @Test
    fun `OTP endpoint rejects codes other than exactly six digits`() {
        listOf("1234", "12345", "1234567", "12345a").forEach { code ->
            mockMvc
                .post("/api/auth/verify-code") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"phoneNumber":"+48111222444","code":"$code"}"""
                }
                .andExpect { status { isBadRequest() } }
        }
    }

    @Test
    fun `request-code with empty phone number should return 400 validation`() {
        val badRequest = """{"phoneNumber": ""}"""

        mockMvc
            .post("/api/auth/request-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = badRequest
            }
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `request-code with invalid phone format should return 400`() {
        val badRequest = """{"phoneNumber": "abc-not-a-phone"}"""

        mockMvc
            .post("/api/auth/request-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = badRequest
            }
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `login-staff with invalid email format should return 400`() {
        val badRequest = """{"email": "to-nie-email", "password": "jakiesHaslo1"}"""

        mockMvc
            .post("/api/auth/login-staff") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = badRequest
            }
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `login-staff with too short password should return 400`() {
        val badRequest = """{"email": "test@test.pl", "password": "abc"}"""

        mockMvc
            .post("/api/auth/login-staff") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = badRequest
            }
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `login-staff should return 429 after exceeding rate limit`() {
        // GIVEN - 10 attempts allowed per minute (application.yaml default)
        val loginDto = StaffLoginRequest(email = "test@salon.pl", password = "jakiesHaslo1", companyId = testId(1))

        repeat(10) {
            mockMvc.post("/api/auth/login-staff") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(loginDto)
            }
        }

        // 11th attempt from the same IP — should be rate limited
        mockMvc
            .post("/api/auth/login-staff") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(loginDto)
            }
            .andExpect {
                status { isTooManyRequests() }
            }
    }

    @Test
    fun `request-code returns 429 during sixty second resend cooldown`() {
        val body = objectMapper.writeValueAsString(RequestCodeRequest("+48555666777"))
        mockMvc
            .post("/api/auth/request-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
            .andExpect { status { isOk() } }
        mockMvc
            .post("/api/auth/request-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
            .andExpect { status { isTooManyRequests() } }
    }

    @Test
    fun `verify-code should retain TTL lock after three failures and reset on replacement code`() {
        val phoneNumber = "+48555111222"
        val requestCode = RequestCodeRequest(phoneNumber)
        mockMvc
            .post("/api/auth/request-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(requestCode)
            }
            .andExpect { status { isOk() } }

        val otpKey = "otp:$phoneNumber"
        val firstCode = requireNotNull(redisTemplate.opsForValue().get(otpKey))
        val initialTtl = requireNotNull(redisTemplate.getExpire(otpKey, TimeUnit.SECONDS))
        val wrongCode = if (firstCode == "000000") "000001" else "000000"

        repeat(2) {
            mockMvc
                .post("/api/auth/verify-code") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(VerifyCodeRequest(phoneNumber, wrongCode))
                }
                .andExpect { status { isUnauthorized() } }
        }

        val ttlAfterFailures = requireNotNull(redisTemplate.getExpire(otpKey, TimeUnit.SECONDS))
        Assertions.assertTrue(ttlAfterFailures > 0)
        Assertions.assertTrue(ttlAfterFailures <= initialTtl)
        assertEquals("$firstCode|2", redisTemplate.opsForValue().get(otpKey))

        mockMvc
            .post("/api/auth/verify-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(VerifyCodeRequest(phoneNumber, wrongCode))
            }
            .andExpect { status { isTooManyRequests() } }

        mockMvc
            .post("/api/auth/verify-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(VerifyCodeRequest(phoneNumber, firstCode, "Jan", "Nowak"))
            }
            .andExpect { status { isTooManyRequests() } }

        redisTemplate.delete("otp:cooldown:$phoneNumber")
        mockMvc
            .post("/api/auth/request-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(requestCode)
            }
            .andExpect { status { isOk() } }

        val replacementCode = requireNotNull(redisTemplate.opsForValue().get(otpKey))
        Assertions.assertFalse(replacementCode.contains('|'))
        mockMvc
            .post("/api/auth/verify-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content =
                    objectMapper.writeValueAsString(
                        VerifyCodeRequest(phoneNumber, replacementCode, "Jan", "Nowak"),
                    )
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.token") { doesNotExist() }
                cookie { exists("SESSION") }
            }
    }

    @Test
    fun `two concurrent correct verify-code requests should issue exactly one session`() {
        val phoneNumber = "+48555222333"
        mockMvc
            .post("/api/auth/request-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(RequestCodeRequest(phoneNumber))
            }
            .andExpect { status { isOk() } }
        val code = requireNotNull(redisTemplate.opsForValue().get("otp:$phoneNumber"))
        val requestBody = objectMapper.writeValueAsString(VerifyCodeRequest(phoneNumber, code, "Jan", "Nowak"))

        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val requests =
                List(2) {
                    executor.submit<Int> {
                        start.await()
                        mockMvc
                            .post("/api/auth/verify-code") {
                                with(csrf())
                                contentType = MediaType.APPLICATION_JSON
                                content = requestBody
                            }
                            .andReturn()
                            .response
                            .status
                    }
                }
            start.countDown()
            val statuses = requests.map { it.get(10, TimeUnit.SECONDS) }.sorted()

            assertEquals(listOf(200, 401), statuses)
            Assertions.assertNotNull(userRepository.findByPhoneNumber(phoneNumber))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `verify-code IP limit should ignore spoofed X-Forwarded-For from untrusted peer`() {
        repeat(10) { attempt ->
            val phoneNumber = "+4860000${attempt.toString().padStart(4, '0')}"
            mockMvc
                .post("/api/auth/verify-code") {
                    with(csrf())
                    header("X-Forwarded-For", "203.0.113.$attempt")
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(VerifyCodeRequest(phoneNumber, "123456"))
                }
                .andExpect { status { isUnauthorized() } }
        }

        mockMvc
            .post("/api/auth/verify-code") {
                with(csrf())
                header("X-Forwarded-For", "203.0.113.99")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(VerifyCodeRequest("+48699999999", "123456"))
            }
            .andExpect { status { isTooManyRequests() } }

        assertEquals(11L, redisTemplate.opsForZSet().zCard("rate:otp-verify:127.0.0.1"))
    }

    @Test
    fun `request-code should return 429 after ten requests from one IP for different numbers`() {
        repeat(10) { attempt ->
            mockMvc
                .post("/api/auth/request-code") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        objectMapper.writeValueAsString(
                            RequestCodeRequest("+4861000${attempt.toString().padStart(4, '0')}"),
                        )
                }
                .andExpect { status { isOk() } }
        }

        mockMvc
            .post("/api/auth/request-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(RequestCodeRequest("+48619999999"))
            }
            .andExpect { status { isTooManyRequests() } }

        Assertions.assertNull(redisTemplate.opsForValue().get("otp:+48619999999"))
    }

    @Test
    fun `logout should revoke only the session used for the request`() {
        val customer = userRepository.save(User(phoneNumber = "+48500100200", firstName = "Jan", lastName = "Nowak"))
        val session = testSessions.customer(customer)
        val otherDeviceSession = testSessions.customer(customer)

        mockMvc.get("/api/users/me") { cookie(session) }.andExpect { status { isOk() } }

        mockMvc
            .post("/api/auth/logout") {
                with(csrf())
                cookie(session)
            }
            .andExpect { status { isNoContent() } }

        mockMvc.get("/api/users/me") { cookie(session) }.andExpect { status { isUnauthorized() } }
        mockMvc
            .post("/api/auth/logout") {
                with(csrf())
                cookie(session)
            }
            .andExpect { status { isNoContent() } }
        mockMvc.get("/api/users/me") { cookie(otherDeviceSession) }.andExpect { status { isOk() } }
    }

    @Test
    fun `logout-all should revoke every session of the user`() {
        val rawPassword = "securePassword1"
        val company = companyRepository.save(Company(name = "Logout Salon"))
        val owner =
            userRepository.save(
                User(
                    phoneNumber = "+48500300400",
                    firstName = "Ola",
                    lastName = "Owner",
                    email = "logout@salon.pl",
                    passwordHash = passwordEncoder.encode(rawPassword),
                ),
            )
        companyEmployeeRepository.save(
            CompanyEmployee(
                companyId = company.id!!,
                userId = owner.id!!,
                role = "OWNER",
                loginEmail = owner.email,
                passwordHash = owner.passwordHash,
            ),
        )
        val sessions =
            List(2) {
                val result =
                    mockMvc
                        .post("/api/auth/login-staff") {
                            with(csrf())
                            contentType = MediaType.APPLICATION_JSON
                            content =
                                objectMapper.writeValueAsString(
                                    StaffLoginRequest("logout@salon.pl", rawPassword, company.id),
                                )
                        }
                        .andExpect { status { isOk() } }
                        .andReturn()
                requireNotNull(result.response.getCookie("SESSION"))
            }

        mockMvc
            .post("/api/auth/logout-all") {
                with(csrf())
                cookie(sessions[0])
            }
            .andExpect { status { isNoContent() } }

        sessions.forEach { session ->
            mockMvc.get("/api/company/settings") { cookie(session) }.andExpect { status { isUnauthorized() } }
        }
    }

    @Test
    fun `logout is idempotent with CSRF even without a session`() {
        mockMvc
            .post("/api/auth/logout") {
                with(csrf())
            }
            .andExpect { status { isNoContent() } }
    }

    private fun loginForEmployment(password: String, employmentId: UUID): Cookie {
        val result =
            mockMvc
                .post("/api/auth/login-staff") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        objectMapper.writeValueAsString(
                            StaffLoginRequest("multi@salon.pl", password, employmentId),
                        )
                }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("AUTHENTICATED") }
                }
                .andReturn()
        return requireNotNull(result.response.getCookie("SESSION"))
    }

    private fun identity(cookie: Cookie) =
        objectMapper.readTree(
            mockMvc
                .get("/api/auth/me") { cookie(cookie) }
                .andExpect { status { isOk() } }
                .andReturn()
                .response
                .contentAsString,
        )

    private fun settingsBody(): String =
        objectMapper.writeValueAsString(
            mapOf(
                "openingTime" to "08:00:00",
                "closingTime" to "20:00:00",
                "slotIntervalMinutes" to 15,
            ),
        )
}
