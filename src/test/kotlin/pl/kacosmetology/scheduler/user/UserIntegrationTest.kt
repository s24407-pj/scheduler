package pl.kacosmetology.scheduler.user

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.treatment.TreatmentRepository
import pl.kacosmetology.scheduler.user.dto.UpdateUserProfileRequest
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class UserIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtService: JwtService

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired
    private lateinit var treatmentRepository: TreatmentRepository

    private lateinit var testUser: User
    private lateinit var jwtToken: String

    @BeforeEach
    fun setup() {
        // Czyszczenie w kolejności odwrotnej do zależności FK
        reservationRepository.deleteAll()
        treatmentRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()

        // Tworzymy użytkownika w bazie
        testUser = userRepository.save(
            User(phoneNumber = "+48111222333", firstName = "Jan", lastName = "Kowalski")
        )

        // Generujemy dla niego token (z null jako companyId, bo to zwykły klient)
        val userDetails = CustomUserDetails(testUser, null, listOf(SimpleGrantedAuthority("ROLE_CUSTOMER")))
        jwtToken = jwtService.generateToken(userDetails, null)
    }

    @Test
    fun `GET api-users-me should return logged in user profile (200)`() {
        mockMvc.get("/api/users/me") {
            header("Authorization", "Bearer $jwtToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.firstName") { value("Jan") }
            jsonPath("$.lastName") { value("Kowalski") }
            jsonPath("$.phoneNumber") { value("+48111222333") }
        }
    }

    @Test
    fun `PUT api-users-me should update profile and save to database (200)`() {
        val updateRequest = UpdateUserProfileRequest(
            firstName = "Piotr",
            lastName = "Nowak",
            email = "piotr.nowak@example.com"
        )

        mockMvc.put("/api/users/me") {
            header("Authorization", "Bearer $jwtToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(updateRequest)
        }.andExpect {
            status { isOk() }
            jsonPath("$.firstName") { value("Piotr") }
            jsonPath("$.email") { value("piotr.nowak@example.com") }
        }

        // Upewniamy się, że zmiana zapisała się w prawdziwej bazie
        val userInDb = userRepository.findById(testUser.id).get()
        assertEquals("Piotr", userInDb.firstName)
        assertEquals("piotr.nowak@example.com", userInDb.email)
    }

    @Test
    fun `PUT api-users-me should return 400 when validation fails`() {
        // Próbujemy wysłać puste imię i niepoprawny adres e-mail
        val badRequest = UpdateUserProfileRequest(
            firstName = "",
            lastName = "Kowalski",
            email = "to-nie-jest-email"
        )

        mockMvc.put("/api/users/me") {
            header("Authorization", "Bearer $jwtToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(badRequest)
        }.andExpect {
            status { isBadRequest() } // Dzięki @Valid powinno natychmiast odrzucić!
        }
    }

    @Test
    fun `should reject request without token (403)`() {
        mockMvc.get("/api/users/me")
            .andExpect {
                status { isForbidden() } // Spring Security odrzuca nieautoryzowany ruch
            }
    }

    @Test
    fun `GET api-users-me should NOT return passwordHash in response`() {
        // GIVEN - użytkownik z hasłem
        userRepository.deleteAll()
        val userWithPassword = userRepository.save(
            User(
                phoneNumber = "+48999111222",
                firstName = "Anna",
                lastName = "Hasłowa",
                email = "anna@test.pl",
                passwordHash = "super_secret_hash"
            )
        )
        val details = CustomUserDetails(userWithPassword, null, listOf(SimpleGrantedAuthority("ROLE_CUSTOMER")))
        val token = jwtService.generateToken(details, null)

        // WHEN & THEN
        mockMvc.get("/api/users/me") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.firstName") { value("Anna") }
            // Kluczowe! Pole passwordHash NIE MOŻE istnieć w odpowiedzi
            jsonPath("$.passwordHash") { doesNotExist() }
            jsonPath("$.password_hash") { doesNotExist() }
        }
    }
}