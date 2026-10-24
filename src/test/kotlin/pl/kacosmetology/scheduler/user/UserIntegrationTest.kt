package pl.kacosmetology.scheduler.user

import com.ninjasquad.springmockk.MockkBean
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.TestSessions
import software.amazon.awssdk.services.s3.S3Client

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class UserIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var userRepository: CompanyCustomerRepository

    @Autowired private lateinit var testSessions: TestSessions

    @Autowired private lateinit var reservationRepository: ReservationRepository

    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired private lateinit var offeringRepository: OfferingRepository

    @MockkBean private lateinit var s3Client: S3Client

    private lateinit var testUser: CompanyCustomer
    private lateinit var sessionCookie: Cookie

    @Autowired private lateinit var companies: pl.kacosmetology.scheduler.company.CompanyRepository
    private lateinit var companyId: java.util.UUID

    @BeforeEach
    fun setup() {
        // Czyszczenie w kolejności odwrotnej do zależności FK
        reservationRepository.deleteAll()
        offeringRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()

        companyId = companies.save(pl.kacosmetology.scheduler.company.Company(name = "Profile")).id!!
        // Tworzymy użytkownika w bazie
        testUser =
            userRepository.save(
                CompanyCustomer(
                    companyId = companyId,
                    phoneNumber = "+48111222333",
                    firstName = "Jan",
                    lastName = "Kowalski",
                ),
            )

        sessionCookie = testSessions.customer(testUser)
    }

    @Test
    fun `GET api-users-me should return logged in user profile (200)`() {
        mockMvc
            .get("/api/users/me") {
                cookie(sessionCookie)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.firstName") { doesNotExist() }
                jsonPath("$.lastName") { doesNotExist() }
                jsonPath("$.phoneNumber") { value("+48111222333") }
            }
    }

    @Test
    fun `PUT api-users-me cannot edit salon owned profiles`() {
        mockMvc
            .put("/api/users/me") {
                with(csrf())
                cookie(sessionCookie)
                contentType = MediaType.APPLICATION_JSON
                content = """{"firstName":"Piotr","lastName":"Nowak","email":null}"""
            }
            .andExpect { status { isMethodNotAllowed() } }
    }

    @Test
    fun `should reject request without session (401)`() {
        mockMvc.get("/api/users/me").andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `GET api-users-me should NOT return passwordHash in response`() {
        // GIVEN - użytkownik z hasłem
        userRepository.deleteAll()
        val userWithPassword =
            userRepository.save(
                CompanyCustomer(
                    companyId = companyId,
                    phoneNumber = "+48999111222",
                    firstName = "Anna",
                    lastName = "Hasłowa",
                ),
            )
        val session = testSessions.customer(userWithPassword)

        // WHEN & THEN
        mockMvc
            .get("/api/users/me") {
                cookie(session)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.firstName") { doesNotExist() }
                // Kluczowe! Pole passwordHash NIE MOŻE istnieć w odpowiedzi
                jsonPath("$.passwordHash") { doesNotExist() }
                jsonPath("$.password_hash") { doesNotExist() }
            }
    }
}
