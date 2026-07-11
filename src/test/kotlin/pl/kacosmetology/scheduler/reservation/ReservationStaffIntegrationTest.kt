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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlock
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlockRepository
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ReservationStaffIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc
    @Autowired
    private lateinit var objectMapper: ObjectMapper
    @Autowired
    private lateinit var jwtService: JwtService
    @Autowired
    private lateinit var userRepository: UserRepository
    @Autowired
    private lateinit var companyRepository: CompanyRepository
    @Autowired
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository
    @Autowired
    private lateinit var serviceRepository: OfferingRepository
    @Autowired
    private lateinit var reservationRepository: ReservationRepository
    @Autowired
    private lateinit var scheduleBlockRepository: ScheduleBlockRepository

    @MockkBean
    private lateinit var s3Client: S3Client

    private lateinit var employee: User
    private var companyId: Long = 0
    private var serviceId: Long = 0
    private lateinit var staffToken: String
    private val reservationTime: LocalDateTime =
        LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0)

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        scheduleBlockRepository.deleteAll()
        serviceRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Salon Integracyjny"))
        companyId = company.id!!

        employee = userRepository.save(User(phoneNumber = "+48700111222", firstName = "Styl", lastName = "Pracownik"))
        val employment = companyEmployeeRepository.save(
            CompanyEmployee(companyId = companyId, userId = employee.id!!, role = "EMPLOYEE")
        )

        val service = serviceRepository.save(
            Offering(companyId = companyId, name = "Koloryzacja", durationMinutes = 90, price = 300)
        )
        serviceId = service.id!!

        staffToken = jwtService.generateStaffToken(employee, employment)
    }

    @Test
    fun `POST reservations-staff should create reservation for existing client`() {
        // GIVEN - Klient już istnieje w bazie
        val existingClient = userRepository.save(
            User(phoneNumber = "+48555444333", firstName = "Istniejący", lastName = "Klient")
        )

        val body = mapOf(
            "employeeId" to employee.id!!,
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
        assertEquals(existingClient.id!!, reservations.first().customerId)
    }

    @Test
    fun `POST reservations-staff should create new client and reservation when phone is unknown`() {
        val newClientPhone = "+48666777888"

        val body = mapOf(
            "employeeId" to employee.id!!,
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
        assertEquals(newClient.id!!, reservations.first().customerId)
    }

    @Test
    fun `POST reservations-staff should return 400 when new client phone but no name provided`() {
        val unknownPhone = "+48100200300"

        val body = mapOf(
            "employeeId" to employee.id!!,
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
    fun `POST reservations-staff should return 400 when service belongs to another company`() {
        val otherCompany = companyRepository.save(Company(name = "Obcy Salon"))
        val otherEmployee = userRepository.save(
            User(phoneNumber = "+48777111222", firstName = "Obcy", lastName = "Pracownik")
        )
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = otherCompany.id!!, userId = otherEmployee.id!!, role = "EMPLOYEE")
        )
        val otherService = serviceRepository.save(
            Offering(companyId = otherCompany.id!!, name = "Obca usługa", durationMinutes = 30, price = 90)
        )
        val unknownPhone = "+48123999888"

        val body = mapOf(
            "employeeId" to otherEmployee.id!!,
            "serviceId" to otherService.id!!,
            "startTime" to reservationTime.toString(),
            "customerPhone" to unknownPhone,
            "customerFirstName" to "Nowy",
            "customerLastName" to "Klient"
        )

        mockMvc.post("/api/reservations/staff") {
            header("Authorization", "Bearer $staffToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isBadRequest() }
        }

        assertEquals(0, reservationRepository.findAll().size)
        assertEquals(null, userRepository.findByPhoneNumber(unknownPhone))
    }

    @Test
    fun `POST reservations-staff should return 403 without token`() {
        val body = mapOf(
            "employeeId" to employee.id!!,
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

    @Test
    fun `POST reservations-staff should return 409 when requested time overlaps schedule block`() {
        val existingClient = userRepository.save(
            User(phoneNumber = "+48555000111", firstName = "Istniejący", lastName = "Klient")
        )
        scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employee.id!!,
                startTime = reservationTime,
                endTime = reservationTime.plusHours(1)
            )
        )
        val body = mapOf(
            "employeeId" to employee.id!!,
            "serviceId" to serviceId,
            "startTime" to reservationTime.plusMinutes(30).toString(),
            "customerPhone" to existingClient.phoneNumber
        )

        mockMvc.post("/api/reservations/staff") {
            header("Authorization", "Bearer $staffToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isConflict() }
        }

        assertEquals(0, reservationRepository.findAll().size)
    }
}
