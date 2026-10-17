package pl.kacosmetology.scheduler.workschedule

import com.ninjasquad.springmockk.MockkBean
import jakarta.servlet.http.Cookie
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.TestSessions
import pl.kacosmetology.scheduler.testId
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import pl.kacosmetology.scheduler.workschedule.dto.SetWeeklyScheduleRequest
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class WorkScheduleIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var testSessions: TestSessions
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var companyRepository: CompanyRepository
    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository
    @Autowired private lateinit var workScheduleRepository: EmployeeWorkScheduleRepository
    @Autowired private lateinit var reservationRepository: ReservationRepository
    @Autowired private lateinit var serviceRepository: OfferingRepository

    @Autowired private lateinit var overrideRepository: EmployeeScheduleOverrideRepository
    @Autowired private lateinit var scheduleService: WorkScheduleService
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var slotPolicy: pl.kacosmetology.scheduler.availability.BookableSlotPolicy
    @MockkBean private lateinit var s3Client: S3Client

    @AfterEach
    fun cleanupOverrides() {
        overrideRepository.deleteAll()
    }

    private var companyId: UUID = testId(0)
    private var employeeId: UUID = testId(0)
    private lateinit var ownerSession: Cookie
    private lateinit var employeeSession: Cookie

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        workScheduleRepository.deleteAll()
        serviceRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Salon Grafik"))
        companyId = company.id!!

        val owner = userRepository.save(User(phoneNumber = "+48111222333", firstName = "Owner", lastName = "Test"))
        val ownerEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyId, userId = owner.id!!, role = "OWNER"),
            )
        ownerSession = testSessions.staff(owner, ownerEmployment)

        val employee = userRepository.save(User(phoneNumber = "+48333222111", firstName = "Emp", lastName = "Test"))
        val employeeEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyId, userId = employee.id!!, role = "EMPLOYEE"),
            )
        employeeId = employee.id!!
        employeeSession = testSessions.staff(employee, employeeEmployment)
    }

    @Test
    fun `PUT work-schedule should return 200 for owner and save entries`() {
        val body =
            mapOf(
                "entries" to
                    listOf(
                        mapOf("dayOfWeek" to "MONDAY", "startTime" to "09:00:00", "endTime" to "17:00:00"),
                        mapOf("dayOfWeek" to "TUESDAY", "startTime" to "10:00:00", "endTime" to "18:00:00"),
                    ),
            )

        mockMvc
            .put("/api/employees/$employeeId/work-schedule") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
            }

        assertEquals(2, workScheduleRepository.findAllByCompanyIdAndEmployeeId(companyId, employeeId).size)
    }

    @Test
    fun `PUT work-schedule at one company should keep the employee's schedule at another company`() {
        val otherCompany = companyRepository.save(Company(name = "Drugi Salon Grafik"))
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = otherCompany.id!!, userId = employeeId, role = "EMPLOYEE"),
        )
        workScheduleRepository.save(
            EmployeeWorkSchedule(
                companyId = otherCompany.id!!,
                employeeId = employeeId,
                dayOfWeek = DayOfWeek.MONDAY,
                startTime = java.time.LocalTime.of(12, 0),
                endTime = java.time.LocalTime.of(20, 0),
            ),
        )
        val body =
            mapOf(
                "entries" to
                    listOf(
                        mapOf("dayOfWeek" to "MONDAY", "startTime" to "08:00:00", "endTime" to "12:00:00"),
                    ),
            )

        mockMvc
            .put("/api/employees/$employeeId/work-schedule") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }
            .andExpect {
                status { isOk() }
            }
        mockMvc
            .get("/api/employees/$employeeId/work-schedule") {
                cookie(ownerSession)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].startTime") { value("08:00:00") }
            }

        val otherSchedule = workScheduleRepository.findAllByCompanyIdAndEmployeeId(otherCompany.id!!, employeeId)
        assertEquals(1, otherSchedule.size)
        assertEquals(java.time.LocalTime.of(12, 0), otherSchedule.first().startTime)
    }

    @Test
    fun `PUT work-schedule should replace existing schedule idempotently`() {
        workScheduleRepository.save(
            EmployeeWorkSchedule(
                companyId = companyId,
                employeeId = employeeId,
                dayOfWeek = DayOfWeek.MONDAY,
                startTime = java.time.LocalTime.of(9, 0),
                endTime = java.time.LocalTime.of(17, 0),
            ),
        )

        val body =
            mapOf(
                "entries" to
                    listOf(
                        mapOf("dayOfWeek" to "WEDNESDAY", "startTime" to "08:00:00", "endTime" to "16:00:00"),
                    ),
            )

        mockMvc
            .put("/api/employees/$employeeId/work-schedule") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].dayOfWeek") { value("WEDNESDAY") }
            }

        assertEquals(1, workScheduleRepository.findAllByCompanyIdAndEmployeeId(companyId, employeeId).size)
    }

    @Test
    fun `PUT work-schedule should replace same day with updated hours without constraint violation`() {
        workScheduleRepository.save(
            EmployeeWorkSchedule(
                companyId = companyId,
                employeeId = employeeId,
                dayOfWeek = DayOfWeek.MONDAY,
                startTime = java.time.LocalTime.of(9, 0),
                endTime = java.time.LocalTime.of(17, 0),
            ),
        )

        val body =
            mapOf(
                "entries" to
                    listOf(
                        mapOf("dayOfWeek" to "MONDAY", "startTime" to "10:00:00", "endTime" to "18:00:00"),
                    ),
            )

        mockMvc
            .put("/api/employees/$employeeId/work-schedule") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].dayOfWeek") { value("MONDAY") }
                jsonPath("$[0].startTime") { value("10:00:00") }
            }

        assertEquals(1, workScheduleRepository.findAllByCompanyIdAndEmployeeId(companyId, employeeId).size)
    }

    @Test
    fun `PUT work-schedule should return 403 for employee`() {
        val body = mapOf("entries" to emptyList<Any>())

        mockMvc
            .put("/api/employees/$employeeId/work-schedule") {
                with(csrf())
                cookie(employeeSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }
            .andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `PUT work-schedule should return 400 when duplicate days provided`() {
        val body =
            mapOf(
                "entries" to
                    listOf(
                        mapOf("dayOfWeek" to "MONDAY", "startTime" to "09:00:00", "endTime" to "17:00:00"),
                        mapOf("dayOfWeek" to "MONDAY", "startTime" to "10:00:00", "endTime" to "18:00:00"),
                    ),
            )

        mockMvc
            .put("/api/employees/$employeeId/work-schedule") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `PUT work-schedule should return 400 when endTime is not after startTime`() {
        val body =
            mapOf(
                "entries" to
                    listOf(
                        mapOf("dayOfWeek" to "FRIDAY", "startTime" to "17:00:00", "endTime" to "09:00:00"),
                    ),
            )

        mockMvc
            .put("/api/employees/$employeeId/work-schedule") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `GET work-schedule should return 200 with current schedule`() {
        workScheduleRepository.save(
            EmployeeWorkSchedule(
                companyId = companyId,
                employeeId = employeeId,
                dayOfWeek = DayOfWeek.THURSDAY,
                startTime = java.time.LocalTime.of(9, 0),
                endTime = java.time.LocalTime.of(17, 0),
            ),
        )

        mockMvc
            .get("/api/employees/$employeeId/work-schedule") {
                cookie(ownerSession)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].dayOfWeek") { value("THURSDAY") }
            }
    }

    @Test
    fun `GET work-schedule should return 200 for employee`() {
        workScheduleRepository.save(
            EmployeeWorkSchedule(
                companyId = companyId,
                employeeId = employeeId,
                dayOfWeek = DayOfWeek.MONDAY,
                startTime = java.time.LocalTime.of(9, 0),
                endTime = java.time.LocalTime.of(17, 0),
            ),
        )

        mockMvc
            .get("/api/employees/$employeeId/work-schedule") {
                cookie(employeeSession)
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].dayOfWeek") { value("MONDAY") }
            }
    }

    @Test
    fun `GET work-schedule should return 403 for customer`() {
        val customer =
            userRepository.save(User(phoneNumber = "+48300300300", firstName = "Customer", lastName = "Test"))
        val customerSession = testSessions.customer(customer)

        mockMvc
            .get("/api/employees/$employeeId/work-schedule") {
                cookie(customerSession)
            }
            .andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `GET work-schedule should return 404 for employee outside owner company`() {
        val otherCompany = companyRepository.save(Company(name = "Inny Salon"))
        val otherEmployee =
            userRepository.save(User(phoneNumber = "+48400400400", firstName = "Other", lastName = "Employee"))
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = otherCompany.id!!, userId = otherEmployee.id!!, role = "EMPLOYEE"),
        )

        mockMvc
            .get("/api/employees/${otherEmployee.id!!}/work-schedule") {
                cookie(ownerSession)
            }
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `missing and null weekly lists reject and preserve schedule while explicit empty clears`() {
        workScheduleRepository.save(
            EmployeeWorkSchedule(
                companyId = companyId,
                employeeId = employeeId,
                dayOfWeek = DayOfWeek.MONDAY,
                startTime = LocalTime.of(9, 0),
                endTime = LocalTime.of(17, 0),
            ),
        )
        listOf("{}", """{"entries":null}""").forEach { payload ->
            mockMvc
                .put("/api/employees/$employeeId/work-schedule") {
                    with(csrf())
                    cookie(ownerSession)
                    contentType = MediaType.APPLICATION_JSON
                    content = payload
                }
                .andExpect { status { isBadRequest() } }
            assertEquals(1, workScheduleRepository.findAllByCompanyIdAndEmployeeId(companyId, employeeId).size)
        }
        mockMvc
            .put("/api/employees/$employeeId/work-schedule") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = """{"entries":[]}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }
    }

    @Test
    fun `owner date replacements and days off replace weekly grid and deletion restores weekly grid`() {
        val date = LocalDate.of(2030, 1, 7)
        workScheduleRepository.save(
            EmployeeWorkSchedule(
                companyId = companyId,
                employeeId = employeeId,
                dayOfWeek = date.dayOfWeek,
                startTime = LocalTime.of(9, 0),
                endTime = LocalTime.of(17, 0),
            ),
        )
        val path = "/api/employees/$employeeId/schedule-overrides/$date"
        mockMvc
            .put(path) {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = """{"dayOff":false,"startTime":"12:15","endTime":"14:15"}"""
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.startTime") { value("12:15:00") }
            }
        assertEquals(date.atTime(12, 15), slotPolicy.slotStarts(companyId, employeeId, date, 60, 30).first())
        mockMvc
            .put(path) {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = """{"dayOff":true}"""
            }
            .andExpect { status { isOk() } }
        assertEquals(emptyList<java.time.LocalDateTime>(), slotPolicy.slotStarts(companyId, employeeId, date, 60, 30))
        mockMvc
            .delete(path) {
                with(csrf())
                cookie(ownerSession)
            }
            .andExpect { status { isNoContent() } }
        assertEquals(date.atTime(9, 0), slotPolicy.slotStarts(companyId, employeeId, date, 60, 30).first())
    }

    @Test
    fun `override validation rejects invalid times employee writes and foreign employee targets`() {
        val path = "/api/employees/$employeeId/schedule-overrides/2030-01-07"
        listOf(
                "{}",
                """{"dayOff":true,"startTime":"10:00"}""",
                """{"dayOff":false,"startTime":"17:00","endTime":"09:00"}""",
            )
            .forEach { payload ->
                mockMvc
                    .put(path) {
                        with(csrf())
                        cookie(ownerSession)
                        contentType = MediaType.APPLICATION_JSON
                        content = payload
                    }
                    .andExpect { status { isBadRequest() } }
            }
        mockMvc
            .put(path) {
                with(csrf())
                cookie(employeeSession)
                contentType = MediaType.APPLICATION_JSON
                content = """{"dayOff":true}"""
            }
            .andExpect { status { isForbidden() } }
        mockMvc
            .put("/api/employees/${testId(99999999)}/schedule-overrides/2030-01-07") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = """{"dayOff":true}"""
            }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `schedule changes wait for the same company lock used by booking policy decisions`() {
        val pool = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        var pending: java.util.concurrent.Future<*>? = null
        try {
            TransactionTemplate(transactions).executeWithoutResult {
                companyRepository.findByIdForUpdate(companyId)!!
                pending = pool.submit {
                    started.countDown()
                    scheduleService.setSchedule(companyId, employeeId, SetWeeklyScheduleRequest(emptyList()))
                }
                org.junit.jupiter.api.Assertions.assertTrue(started.await(2, TimeUnit.SECONDS))
                assertThrows<TimeoutException> { pending!!.get(200, TimeUnit.MILLISECONDS) }
            }
            pending!!.get(5, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }
}
