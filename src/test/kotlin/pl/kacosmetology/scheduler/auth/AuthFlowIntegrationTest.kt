package pl.kacosmetology.scheduler.auth

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.junit.jupiter.api.Assertions
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
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.StaffLoginRequest
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
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
    private lateinit var passwordEncoder: PasswordEncoder

    // Zastępujemy prawdziwy serwis sztucznym (żeby nie sypało logami ani nie wysyłało prawdziwych SMS)
    @MockkBean
    private lateinit var smsSender: SmsSender

    @BeforeEach
    fun setup() {
        // Czyścimy Redis i bazę przed każdym testem
        redisTemplate.connectionFactory?.connection?.serverCommands()?.flushAll()
        userRepository.deleteAll()

        // MÓWIMY MOCKOWI JAK MA SIĘ ZACHOWAĆ:
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
        Assertions.assertEquals("Jan", createdUser?.firstName)

        val codeAfterVerification = redisTemplate.opsForValue().get("otp:$phoneNumber")
        Assertions.assertNull(codeAfterVerification, "Zużyty kod powinien zostac usuniety z Redis")
    }

    @Test
    fun `staff login with email and password should return token`() {
        // 1. GIVEN - Mamy pracownika w bazie z ustawionym hasłem
        val rawPassword = "superSecretPassword"
        userRepository.save(
            User(
                phoneNumber = "+48999888777",
                firstName = "Anna",
                lastName = "Manager",
                email = "anna@salon.pl",
                passwordHash = passwordEncoder.encode(rawPassword)
            )
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
            jsonPath("$.token") { exists() }
        }
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
}