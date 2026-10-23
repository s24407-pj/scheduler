package pl.kacosmetology.scheduler.offering

import com.ninjasquad.springmockk.MockkBean
import java.util.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.*
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.offering.dto.OfferingRequest
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
class OfferingManagementIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var offeringRepository: OfferingRepository

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var companyRepository: CompanyRepository

    @Autowired private lateinit var reservationRepository: ReservationRepository

    @Autowired private lateinit var testSessions: TestSessions

    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @MockkBean private lateinit var s3Client: S3Client

    private lateinit var userA: User
    private lateinit var userB: User
    private lateinit var employmentA: CompanyEmployee
    private lateinit var employmentB: CompanyEmployee

    private var companyA_Id: UUID = testId(0)
    private var companyB_Id: UUID = testId(0)

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        offeringRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val companyA = companyRepository.save(Company(name = "Salon A"))
        val companyB = companyRepository.save(Company(name = "Salon B"))

        companyA_Id = companyA.id!!
        companyB_Id = companyB.id!!

        userA = userRepository.save(User(phoneNumber = "+48111222333", firstName = "Admin", lastName = "FirmaA"))
        userB = userRepository.save(User(phoneNumber = "+48999888777", firstName = "Admin", lastName = "FirmaB"))

        employmentA =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyA_Id, userId = userA.id!!, role = "OWNER"),
            )
        employmentB =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyB_Id, userId = userB.id!!, role = "OWNER"),
            )
    }

    @Test
    fun `should reject offering creation request without session (401)`() {
        val request = OfferingRequest(name = "Masaż", durationMinutes = 60, price = 200)

        mockMvc
            .post("/api/offerings") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `should create offering and assign to company from session (201)`() {
        val sessionA = testSessions.staff(userA, employmentA)
        val request = OfferingRequest(name = "Masaż", durationMinutes = 60, price = 200)

        mockMvc
            .post("/api/offerings") {
                with(csrf())
                cookie(sessionA)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(request)
            }
            .andExpect {
                status { isCreated() }
                jsonPath("$.name") { value("Masaż") }
                jsonPath("$.companyId") { value(companyA_Id.toString()) }
            }
    }

    @Test
    fun `employee of Company B cannot delete offering belonging to Company A (404)`() {
        val offeringOfCompanyA =
            offeringRepository.save(
                Offering(companyId = companyA_Id, name = "Złota Usługa", durationMinutes = 30, price = 500),
            )

        val sessionB = testSessions.staff(userB, employmentB)

        mockMvc
            .delete("/api/offerings/${offeringOfCompanyA.id!!}") {
                with(csrf())
                cookie(sessionB)
            }
            .andExpect {
                status { isNotFound() }
            }

        val isStillInDb = offeringRepository.existsById(offeringOfCompanyA.id!!)
        assertTrue(isStillInDb, "Usługa powinna zostać nietknięta w bazie danych!")
    }

    @Test
    fun `should return only active offerings on public endpoint (200)`() {
        offeringRepository.save(Offering(companyId = companyA_Id, name = "Aktywna", durationMinutes = 30, price = 100))
        offeringRepository.save(
            Offering(
                companyId = companyA_Id,
                name = "Nieaktywna",
                durationMinutes = 30,
                price = 100,
                active = false,
            ),
        )

        mockMvc.get("/api/offerings/public/company/$companyA_Id").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].name") { value("Aktywna") }
        }
    }

    @Test
    fun `should return offering by id for authenticated user of the same company (200)`() {
        val offering =
            offeringRepository.save(
                Offering(companyId = companyA_Id, name = "Masaż", durationMinutes = 60, price = 200),
            )
        val sessionA = testSessions.staff(userA, employmentA)

        mockMvc
            .get("/api/offerings/${offering.id!!}") {
                cookie(sessionA)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(offering.id!!.toString()) }
                jsonPath("$.name") { value("Masaż") }
            }
    }

    @Test
    fun `should activate inactive offering (204)`() {
        val inactive =
            offeringRepository.save(
                Offering(companyId = companyA_Id, name = "Stara", durationMinutes = 30, price = 50, active = false),
            )
        val sessionA = testSessions.staff(userA, employmentA)

        mockMvc
            .patch("/api/offerings/${inactive.id!!}/activate") {
                with(csrf())
                cookie(sessionA)
            }
            .andExpect {
                status { isNoContent() }
            }

        val reactivated = offeringRepository.findById(inactive.id!!).get()
        assertTrue(reactivated.active, "Usługa powinna być aktywna po aktywacji")
    }

    @Test
    fun `employee of Company B cannot activate offering belonging to Company A (404)`() {
        val inactive =
            offeringRepository.save(
                Offering(companyId = companyA_Id, name = "Stara", durationMinutes = 30, price = 50, active = false),
            )
        val sessionB = testSessions.staff(userB, employmentB)

        mockMvc
            .patch("/api/offerings/${inactive.id!!}/activate") {
                with(csrf())
                cookie(sessionB)
            }
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `should update offering when user is employee of the correct company (200)`() {
        val initialOffering =
            offeringRepository.save(
                Offering(companyId = companyA_Id, name = "Stara Nazwa", durationMinutes = 30, price = 100),
            )

        val sessionA = testSessions.staff(userA, employmentA)

        val updateRequest = OfferingRequest(name = "Nowa Nazwa", durationMinutes = 45, price = 250)

        mockMvc
            .put("/api/offerings/${initialOffering.id!!}") {
                with(csrf())
                cookie(sessionA)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(updateRequest)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.name") { value("Nowa Nazwa") }
                jsonPath("$.price") { value(250) }
                jsonPath("$.durationMinutes") { value(45) }
            }

        val updatedOfferingFromDb = offeringRepository.findById(initialOffering.id!!).get()
        assertEquals(250, updatedOfferingFromDb.price)
    }
}
