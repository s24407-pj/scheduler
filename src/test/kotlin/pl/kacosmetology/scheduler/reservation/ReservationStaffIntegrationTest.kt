package pl.kacosmetology.scheduler.reservation

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import software.amazon.awssdk.services.s3.S3Client
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ReservationStaffIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var companyRepository: CompanyRepository
    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository
    @Autowired private lateinit var serviceRepository: OfferingRepository
    @Autowired private lateinit var reservationRepository: ReservationRepository

    @MockkBean private lateinit var s3Client: S3Client

    private lateinit var employee: User
    private var companyId: Long = 0
    private var serviceId: Long = 0
    private lateinit var staffToken: String
    private val reservationTime: LocalDateTime = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0)

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        serviceRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Salon Integracyjny"))
        companyId = company.id!!

        employee = userRepository.save(User(phoneNumber = "+48700111222", firstName = "Styl", lastName = "Pracownik"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = employee.id, role = "EMPLOYEE"))

        val service = serviceRepository.save(
            Offering(companyId = companyId, name = "Koloryzacja", durationMinutes = 90, price = 300)
        )
        serviceId = service.id!!

        staffToken = jwtService.generateToken(
            CustomUserDetails(employee, companyId, listOf(SimpleGrantedAuthority("ROLE_EMPLOYEE"))),
            companyId
        )
    }

    @Test
    fun `POST reservations-staff should create reservation for existing client`() {
        // GIVEN - Klient już istnieje w bazie
        val existingClient = userRepository.save(
            User(phoneNumber = "+48555444333", firstName = "Istniejący", lastName = "Klient")
        )

        val body = mapOf(
            "employeeId" to employee.id,
            "serviceId" to serviceId,
            "startTime" to reservationTime.toString(),
            "customerPhone" to existingClient.phoneNumber
        )

        // WHEN & THEN
        mockMvc.post("/api/reservations/staff") {
            header("Authorization", "Bearer $staffToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.serviceId") { value(serviceId) }
            jsonPath("$.price") { value(300) }
        }

        val reservations = reservationRepository.findAll()
        assertEquals(1, reservations.size)
        assertEquals(existingClient.id, reservations.first().customerId)
    }

    @Test
    fun `POST reservations-staff should create new client and reservation when phone is unknown`() {
        val newClientPhone = "+48666777888"

        val body = mapOf(
            "employeeId" to employee.id,
            "serviceId" to serviceId,
            "startTime" to reservationTime.toString(),
            "customerPhone" to newClientPhone,
            "customerFirstName" to "Nowy",
            "customerLastName" to "Klient"
        )

        mockMvc.post("/api/reservations/staff") {
            header("Authorization", "Bearer $staffToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isCreated() }
        }

        // Sprawdzamy, że nowy klient pojawił się w bazie
        val newClient = userRepository.findByPhoneNumber(newClientPhone)
        assertNotNull(newClient, "Nowy klient powinien zostać zapisany w bazie")
        assertEquals("Nowy", newClient!!.firstName)

        val reservations = reservationRepository.findAll()
        assertEquals(1, reservations.size)
        assertEquals(newClient.id, reservations.first().customerId)
    }

    @Test
    fun `POST reservations-staff should return 400 when new client phone but no name provided`() {
        val unknownPhone = "+48100200300"

        val body = mapOf(
            "employeeId" to employee.id,
            "serviceId" to serviceId,
            "startTime" to reservationTime.toString(),
            "customerPhone" to unknownPhone
            // Brak imienia i nazwiska dla nowego klienta!
        )

        mockMvc.post("/api/reservations/staff") {
            header("Authorization", "Bearer $staffToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isBadRequest() }
        }

        // Upewniamy się, że żadna rezerwacja ani użytkownik nie zostali zapisani
        assertEquals(0, reservationRepository.findAll().size)
        val userInDb = userRepository.findByPhoneNumber(unknownPhone)
        assertEquals(null, userInDb)
    }

    @Test
    fun `POST reservations-staff should return 403 without token`() {
        val body = mapOf(
            "employeeId" to employee.id,
            "serviceId" to serviceId,
            "startTime" to reservationTime.toString(),
            "customerPhone" to "+48000000000"
        )

        mockMvc.post("/api/reservations/staff") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isForbidden() }
        }
    }
}
