package pl.kacosmetology.scheduler.auth

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginRequest
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AuthFlowIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var companyRepository: CompanyRepository

    @Autowired
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired
    private lateinit var jwtService: JwtService

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    // Zastępujemy prawdziwy serwis sztucznym (żeby nie sypało logami ani nie wysyłało prawdziwych SMS)
    @MockkBean
    private lateinit var smsSender: SmsSender

    @MockkBean
    private lateinit var s3Client: S3Client

    @BeforeEach
    fun setup() {
        // Czyścimy Redis i bazę przed każdym testem
        redisTemplate.connectionFactory?.connection?.serverCommands()?.flushAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        every { smsSender.sendOtp(any(), any()) } just Runs
    }

    @Test
    fun `full customer registration and login via SMS should return token`() {
        val phoneNumber = "+48111222333"

        // 1. KROK 1: Klient prosi o kod SMS
        val requestDto = RequestCodeRequest(phoneNumber = phoneNumber)

        mockMvc.post("/api/auth/request-code") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(requestDto)
        }.andExpect {
            status { isOk() }
            jsonPath("$.message") { exists() }
        }

        // 2. Odczytujemy kod z Redis (symulacja odczytania SMS)
        val otpCode = redisTemplate.opsForValue().get("otp:$phoneNumber")
        Assertions.assertNotNull(otpCode, "Kod OTP powinien zostac zapisany w Redis")

        // 3. KROK 2: Klient wysyła kod do weryfikacji wraz z danymi (bo to jego pierwszy raz)
        val verifyDto = VerifyCodeRequest(
            phoneNumber = phoneNumber,
            code = otpCode!!,
            firstName = "Jan",
            lastName = "Kowalski"
        )

        mockMvc.post("/api/auth/verify-code") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(verifyDto)
        }.andExpect {
            status { isOk() }
            jsonPath("$.token") { exists() } // Sprawdzamy, czy system wydał JWT!
        }

        // 4. Upewniamy się, że użytkownik został stworzony w bazie i zużyty kod zniknął
        val createdUser = userRepository.findByPhoneNumber(phoneNumber)
        Assertions.assertNotNull(createdUser, "Uzytkownik powinien zostac utworzony")
        assertEquals("Jan", createdUser?.firstName)

        val codeAfterVerification = redisTemplate.opsForValue().get("otp:$phoneNumber")
        Assertions.assertNull(codeAfterVerification, "Zużyty kod powinien zostac usuniety z Redis")
    }

    @Test
    fun `staff login with email and password should return token`() {
        // 1. GIVEN - Mamy pracownika w bazie z ustawionym hasłem
        val rawPassword = "superSecretPassword"
        val staff = userRepository.save(
            User(
                phoneNumber = "+48999888777",
                firstName = "Anna",
                lastName = "Manager",
                email = "anna@salon.pl",
                passwordHash = passwordEncoder.encode(rawPassword)
            )
        )
        val company = companyRepository.save(Company(name = "Anna Salon"))
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = company.id!!, userId = staff.id, role = "EMPLOYEE")
        )

        // 2. WHEN - Pracownik próbuje się zalogować
        val loginDto = StaffLoginRequest(
            email = "anna@salon.pl",
            password = rawPassword
        )

        // 3. THEN - Oczekujemy 200 OK i tokena JWT
        mockMvc.post("/api/auth/login-staff") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(loginDto)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("AUTHENTICATED") }
            jsonPath("$.token") { exists() }
            jsonPath("$.employments") { isEmpty() }
        }
    }

    @Test
    fun `staff login as owner should return token with role owner`() {
        // GIVEN
        val rawPassword = "securePassword1"
        val company = companyRepository.save(Company(name = "Test Salon"))
        val owner = userRepository.save(
            User(
                phoneNumber = "+48777666555",
                firstName = "Właściciel",
                lastName = "Salonu",
                email = "owner@salon.pl",
                passwordHash = passwordEncoder.encode(rawPassword)
            )
        )
        companyEmployeeRepository.save(CompanyEmployee(companyId = company.id!!, userId = owner.id, role = "OWNER"))

        // WHEN
        val result = mockMvc.post("/api/auth/login-staff") {
            contentType = MediaType.APPLICATION_JSON
            content =
                objectMapper.writeValueAsString(StaffLoginRequest(email = "owner@salon.pl", password = rawPassword))
        }.andExpect {
            status { isOk() }
        }.andReturn()

        val token = objectMapper.readTree(result.response.contentAsString)["token"].asString()

        // THEN
        assertEquals("owner", jwtService.extractRole(token), "Token powinien zawierać rolę owner")
        assertEquals(company.id, jwtService.extractCompanyId(token))
        Assertions.assertNotNull(jwtService.extractEmploymentId(token))
    }

    @Test
    fun `multi-company staff login should bind authorization to selected employment`() {
        val rawPassword = "securePassword1"
        val companyB = companyRepository.save(Company(name = "Beta Salon"))
        val companyA = companyRepository.save(Company(name = "Alpha Salon"))
        val staff = userRepository.save(
            User(
                phoneNumber = "+48700111222",
                firstName = "Multi",
                lastName = "Staff",
                email = "multi@salon.pl",
                passwordHash = passwordEncoder.encode(rawPassword)
            )
        )
        val ownerInB = companyEmployeeRepository.save(
            CompanyEmployee(companyId = companyB.id!!, userId = staff.id, role = "OWNER")
        )
        val employeeInA = companyEmployeeRepository.save(
            CompanyEmployee(companyId = companyA.id!!, userId = staff.id, role = "EMPLOYEE")
        )

        val selectionResult = mockMvc.post("/api/auth/login-staff") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                StaffLoginRequest(email = "multi@salon.pl", password = rawPassword)
            )
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("EMPLOYMENT_SELECTION_REQUIRED") }
            jsonPath("$.token") { doesNotExist() }
            jsonPath("$.employments[0].employmentId") { value(employeeInA.id) }
            jsonPath("$.employments[0].companyName") { value("Alpha Salon") }
            jsonPath("$.employments[1].employmentId") { value(ownerInB.id) }
            jsonPath("$.employments[1].companyName") { value("Beta Salon") }
        }.andReturn()
        Assertions.assertTrue(selectionResult.response.contentAsString.contains("EMPLOYMENT_SELECTION_REQUIRED"))

        val employeeToken = loginForEmployment(rawPassword, employeeInA.id!!)
        assertEquals("employee", jwtService.extractRole(employeeToken))
        assertEquals(companyA.id, jwtService.extractCompanyId(employeeToken))
        assertEquals(employeeInA.id, jwtService.extractEmploymentId(employeeToken))
        mockMvc.put("/api/company/settings") {
            header("Authorization", "Bearer $employeeToken")
            contentType = MediaType.APPLICATION_JSON
            content = settingsBody()
        }.andExpect { status { isForbidden() } }

        val ownerToken = loginForEmployment(rawPassword, ownerInB.id!!)
        assertEquals("owner", jwtService.extractRole(ownerToken))
        assertEquals(companyB.id, jwtService.extractCompanyId(ownerToken))
        mockMvc.get("/api/company/settings") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(companyB.id) }
        }

        companyEmployeeRepository.delete(employeeInA)
        mockMvc.get("/api/company/settings") {
            header("Authorization", "Bearer $employeeToken")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `staff login should reject account without employment`() {
        val password = "securePassword1"
        userRepository.save(
            User(
                phoneNumber = "+48700999888",
                firstName = "No",
                lastName = "Employment",
                email = "none@salon.pl",
                passwordHash = passwordEncoder.encode(password)
            )
        )

        mockMvc.post("/api/auth/login-staff") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(StaffLoginRequest("none@salon.pl", password))
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `loginStaff with wrong password should return 400`() {
        // GIVEN
        userRepository.save(
            User(
                phoneNumber = "+48999888777",
                firstName = "Anna",
                lastName = "Manager",
                email = "anna@salon.pl",
                passwordHash = passwordEncoder.encode("poprawneHaslo")
            )
        )

        val loginDto = StaffLoginRequest(email = "anna@salon.pl", password = "zleHaslo")

        // WHEN & THEN
        mockMvc.post("/api/auth/login-staff") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(loginDto)
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `loginStaff with non-existent email should return 400`() {
        val loginDto = StaffLoginRequest(email = "nieistnieje@salon.pl", password = "jakiesHaslo1")

        mockMvc.post("/api/auth/login-staff") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(loginDto)
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `request-code with empty phone number should return 400 validation`() {
        val badRequest = """{"phoneNumber": ""}"""

        mockMvc.post("/api/auth/request-code") {
            contentType = MediaType.APPLICATION_JSON
            content = badRequest
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `request-code with invalid phone format should return 400`() {
        val badRequest = """{"phoneNumber": "abc-not-a-phone"}"""

        mockMvc.post("/api/auth/request-code") {
            contentType = MediaType.APPLICATION_JSON
            content = badRequest
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `login-staff with invalid email format should return 400`() {
        val badRequest = """{"email": "to-nie-email", "password": "jakiesHaslo1"}"""

        mockMvc.post("/api/auth/login-staff") {
            contentType = MediaType.APPLICATION_JSON
            content = badRequest
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `login-staff with too short password should return 400`() {
        val badRequest = """{"email": "test@test.pl", "password": "abc"}"""

        mockMvc.post("/api/auth/login-staff") {
            contentType = MediaType.APPLICATION_JSON
            content = badRequest
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `login-staff should return 429 after exceeding rate limit`() {
        // GIVEN - 10 attempts allowed per minute (application.yaml default)
        val loginDto = StaffLoginRequest(email = "test@salon.pl", password = "jakiesHaslo1")

        repeat(10) {
            mockMvc.post("/api/auth/login-staff") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(loginDto)
            }
        }

        // 11th attempt from the same IP — should be rate limited
        mockMvc.post("/api/auth/login-staff") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(loginDto)
        }.andExpect {
            status { isTooManyRequests() }
        }
    }

    @Test
    fun `request-code should return 429 after exceeding rate limit`() {
        val phoneNumber = "+48555666777"
        val requestDto = RequestCodeRequest(phoneNumber = phoneNumber)

        // Wysyłamy 3 razy — powinno być OK
        repeat(3) {
            mockMvc.post("/api/auth/request-code") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(requestDto)
            }.andExpect {
                status { isOk() }
            }
        }

        // 4. próba — powinien zwrócić 429 Too Many Requests
        mockMvc.post("/api/auth/request-code") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(requestDto)
        }.andExpect {
            status { isTooManyRequests() }
        }
    }

    private fun loginForEmployment(password: String, employmentId: Long): String {
        val result = mockMvc.post("/api/auth/login-staff") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                StaffLoginRequest("multi@salon.pl", password, employmentId)
            )
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("AUTHENTICATED") }
            jsonPath("$.employments") { isEmpty() }
        }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)["token"].asString()
    }

    private fun settingsBody(): String = objectMapper.writeValueAsString(
        mapOf(
            "openingTime" to "08:00:00",
            "closingTime" to "20:00:00",
            "slotIntervalMinutes" to 15
        )
    )
}
