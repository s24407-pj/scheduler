package pl.kacosmetology.scheduler.scheduleblock

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkSchedule
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkScheduleRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ScheduleBlockIntegrationTest {

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
    private lateinit var scheduleBlockRepository: ScheduleBlockRepository
    @Autowired
    private lateinit var reservationRepository: ReservationRepository
    @Autowired
    private lateinit var serviceRepository: OfferingRepository
    @Autowired
    private lateinit var workScheduleRepository: EmployeeWorkScheduleRepository

    @MockkBean
    private lateinit var s3Client: S3Client

    private lateinit var employee: User
    private lateinit var owner: User
    private var companyId: Long = 0
    private lateinit var employeeToken: String
    private lateinit var ownerToken: String
    private val testDate = LocalDate.now().plusDays(3)

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        scheduleBlockRepository.deleteAll()
        workScheduleRepository.deleteAll()
        serviceRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Salon Testowy"))
        companyId = company.id!!
        employee =
            userRepository.save(User(phoneNumber = "+48700800900", firstName = "Pracownik", lastName = "Testowy"))
        owner = userRepository.save(User(phoneNumber = "+48111222333", firstName = "Właściciel", lastName = "Testowy"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = employee.id, role = "EMPLOYEE"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = owner.id, role = "OWNER"))

        // Grafik pracownika: pracuje cały tydzień 9:00-17:00
        for (day in java.time.DayOfWeek.values()) {
            workScheduleRepository.save(
                EmployeeWorkSchedule(
                    companyId = companyId,
                    employeeId = employee.id,
                    dayOfWeek = day,
                    startTime = LocalTime.of(9, 0),
                    endTime = LocalTime.of(17, 0)
                )
            )
        }

        employeeToken = jwtService.generateToken(
            CustomUserDetails(employee, companyId, listOf(SimpleGrantedAuthority("ROLE_EMPLOYEE"))),
            companyId
        )
        ownerToken = jwtService.generateToken(
            CustomUserDetails(owner, companyId, listOf(SimpleGrantedAuthority("ROLE_OWNER"))),
            companyId
        )
    }

    @Test
    fun `POST schedule-blocks should create block and return 201`() {
        val body = mapOf(
            "startTime" to testDate.atTime(12, 0).toString(),
            "endTime" to testDate.atTime(13, 0).toString(),
            "reason" to "Przerwa obiadowa"
        )

        mockMvc.post("/api/schedule-blocks") {
            header("Authorization", "Bearer $employeeToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
            jsonPath("$.reason") { value("Przerwa obiadowa") }
        }

        assertEquals(1, scheduleBlockRepository.findAll().size)
    }

    @Test
    fun `POST schedule-blocks should return 400 when startTime is in the past`() {
        val body = mapOf(
            "startTime" to LocalDateTime.now().minusHours(1).toString(),
            "endTime" to LocalDateTime.now().plusHours(1).toString()
        )

        mockMvc.post("/api/schedule-blocks") {
            header("Authorization", "Bearer $employeeToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `POST schedule-blocks should return 409 when overlapping block exists`() {
        // Wstawiamy blokadę ręcznie
        scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employee.id,
                startTime = testDate.atTime(12, 0),
                endTime = testDate.atTime(13, 0)
            )
        )

        val body = mapOf(
            "startTime" to testDate.atTime(12, 30).toString(),
            "endTime" to testDate.atTime(13, 30).toString()
        )

        mockMvc.post("/api/schedule-blocks") {
            header("Authorization", "Bearer $employeeToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isConflict() }
        }
    }

    @Test
    fun `DELETE schedule-blocks should remove block and return 204`() {
        val block = scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employee.id,
                startTime = testDate.atTime(14, 0),
                endTime = testDate.atTime(15, 0)
            )
        )

        mockMvc.delete("/api/schedule-blocks/${block.id}") {
            header("Authorization", "Bearer $employeeToken")
        }.andExpect {
            status { isNoContent() }
        }

        assertTrue(scheduleBlockRepository.findAll().isEmpty())
    }

    @Test
    fun `DELETE schedule-blocks should return 409 when trying to delete another employee block`() {
        val otherEmployee = userRepository.save(
            User(phoneNumber = "+48600100200", firstName = "Inny", lastName = "Pracownik")
        )
        val block = scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = otherEmployee.id,
                startTime = testDate.atTime(10, 0),
                endTime = testDate.atTime(11, 0)
            )
        )

        mockMvc.delete("/api/schedule-blocks/${block.id}") {
            header("Authorization", "Bearer $employeeToken")
        }.andExpect {
            status { isConflict() }
        }
    }

    @Test
    fun `GET schedule-blocks employee should return blocks in range`() {
        scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employee.id,
                startTime = testDate.atTime(9, 0),
                endTime = testDate.atTime(10, 0),
                reason = "Spotkanie"
            )
        )
        scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employee.id,
                startTime = testDate.atTime(14, 0),
                endTime = testDate.atTime(15, 0),
                reason = "Szkolenie"
            )
        )

        val start = testDate.atStartOfDay()
        val end = testDate.plusDays(1).atStartOfDay()

        mockMvc.get("/api/schedule-blocks/employee") {
            header("Authorization", "Bearer $employeeToken")
            param("start", start.toString())
            param("end", end.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
        }
    }

    @Test
    fun `schedule block should make slot unavailable in availability endpoint`() {
        // Blokujemy pracownika od 10:00 do 11:00
        scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employee.id,
                startTime = testDate.atTime(10, 0),
                endTime = testDate.atTime(11, 0)
            )
        )

        val service = serviceRepository.save(
            Offering(companyId = companyId, name = "Strzyżenie", durationMinutes = 60, price = 80)
        )

        mockMvc.get("/api/availability") {
            param("employeeId", employee.id.toString())
            param("serviceId", service.id.toString())
            param("date", testDate.toString())
        }.andExpect {
            status { isOk() }
            // Slot 9:30 do 10:30 nachodzi na blokadę 10:00-11:00 -> nie powinien istnieć
            jsonPath("$[?(@.time == '09:30:00')]") { doesNotExist() }
            // Slot 10:00 do 11:00 pokrywa się z blokadą -> nie powinien istnieć
            jsonPath("$[?(@.time == '10:00:00')]") { doesNotExist() }
            // Slot 11:00 do 12:00 jest po blokadzie -> powinien istnieć
            jsonPath("$[?(@.time == '11:00:00')]") { exists() }
        }
    }

    @Test
    fun `POST schedule-blocks should return 403 without token`() {
        val body = mapOf(
            "startTime" to testDate.atTime(10, 0).toString(),
            "endTime" to testDate.atTime(11, 0).toString()
        )

        mockMvc.post("/api/schedule-blocks") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `POST schedule-blocks owner can create block for another employee by supplying employeeId`() {
        val body = mapOf(
            "startTime" to testDate.atTime(12, 0).toString(),
            "endTime" to testDate.atTime(13, 0).toString(),
            "reason" to "Przerwa pracownika",
            "employeeId" to employee.id
        )

        mockMvc.post("/api/schedule-blocks") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect {
            status { isCreated() }
        }

        val blocks = scheduleBlockRepository.findAll()
        assertEquals(1, blocks.size)
        assertEquals(employee.id, blocks[0].employeeId)
    }

    @Test
    fun `DELETE schedule-blocks owner can delete another employee block in their company`() {
        val block = scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employee.id,
                startTime = testDate.atTime(14, 0),
                endTime = testDate.atTime(15, 0)
            )
        )

        mockMvc.delete("/api/schedule-blocks/${block.id}") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isNoContent() }
        }

        assertTrue(scheduleBlockRepository.findAll().isEmpty())
    }

    @Test
    fun `DELETE schedule-blocks owner cannot delete block from another company`() {
        val otherCompany = companyRepository.save(Company(name = "Inny Salon"))
        val otherEmployee =
            userRepository.save(User(phoneNumber = "+48999888777", firstName = "Obcy", lastName = "Pracownik"))
        val block = scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = otherCompany.id!!,
                employeeId = otherEmployee.id,
                startTime = testDate.atTime(10, 0),
                endTime = testDate.atTime(11, 0)
            )
        )

        mockMvc.delete("/api/schedule-blocks/${block.id}") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isConflict() }
        }
    }
}
