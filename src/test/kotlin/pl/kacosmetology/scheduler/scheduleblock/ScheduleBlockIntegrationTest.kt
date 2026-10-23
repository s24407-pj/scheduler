package pl.kacosmetology.scheduler.scheduleblock

import com.ninjasquad.springmockk.MockkBean
import jakarta.servlet.http.Cookie
import java.time.*
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.*
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.security.TestSessions
import pl.kacosmetology.scheduler.testId
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkSchedule
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkScheduleRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ScheduleBlockIntegrationTest {

    @Autowired private lateinit var clock: Clock
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var testSessions: TestSessions
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var companyRepository: CompanyRepository
    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository
    @Autowired private lateinit var scheduleBlockRepository: ScheduleBlockRepository
    @Autowired private lateinit var reservationRepository: ReservationRepository
    @Autowired private lateinit var serviceRepository: OfferingRepository
    @Autowired private lateinit var workScheduleRepository: EmployeeWorkScheduleRepository

    @MockkBean private lateinit var s3Client: S3Client

    private lateinit var employee: User
    private lateinit var owner: User
    private var companyId: UUID = testId(0)
    private lateinit var employeeSession: Cookie
    private lateinit var ownerSession: Cookie
    private val testDate
        get() = LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE).plusDays(3)

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
        val employeeEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyId, userId = employee.id!!, role = "EMPLOYEE"),
            )
        val ownerEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = companyId, userId = owner.id!!, role = "OWNER"),
            )

        // Grafik pracownika: pracuje cały tydzień 9:00-17:00
        for (day in DayOfWeek.entries) {
            workScheduleRepository.save(
                EmployeeWorkSchedule(
                    companyId = companyId,
                    employeeId = employee.id!!,
                    dayOfWeek = day,
                    startTime = LocalTime.of(9, 0),
                    endTime = LocalTime.of(17, 0),
                ),
            )
        }

        employeeSession = testSessions.staff(employee, employeeEmployment)
        ownerSession = testSessions.staff(owner, ownerEmployment)
    }

    @Test
    fun `POST schedule-blocks should create block and return 201`() {
        val body =
            mapOf(
                "startTime" to testDate.atTime(12, 0).toString(),
                "endTime" to testDate.atTime(13, 0).toString(),
                "reason" to "Przerwa obiadowa",
            )

        mockMvc
            .post("/api/schedule-blocks") {
                with(csrf())
                cookie(employeeSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }
            .andExpect {
                status { isCreated() }
                jsonPath("$.id") { exists() }
                jsonPath("$.reason") { value("Przerwa obiadowa") }
            }

        assertEquals(1, scheduleBlockRepository.findAll().size)
    }

    @Test
    fun `POST schedule-blocks permits an ongoing block`() {
        val body =
            mapOf(
                "startTime" to
                    LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE).withNano(0).minusHours(1).toString(),
                "endTime" to
                    LocalDateTime.ofInstant(clock.instant(), BUSINESS_ZONE).withNano(0).plusHours(1).toString(),
            )

        mockMvc
            .post("/api/schedule-blocks") {
                with(csrf())
                cookie(employeeSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }
            .andExpect {
                status { isCreated() }
            }
    }

    @Test
    fun `POST schedule-blocks should return 409 when overlapping block exists`() {
        // Wstawiamy blokadę ręcznie
        scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employee.id!!,
                startTime = testDate.atTime(12, 0),
                endTime = testDate.atTime(13, 0),
            ),
        )

        val body =
            mapOf(
                "startTime" to testDate.atTime(12, 30).toString(),
                "endTime" to testDate.atTime(13, 30).toString(),
            )

        mockMvc
            .post("/api/schedule-blocks") {
                with(csrf())
                cookie(employeeSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }
            .andExpect {
                status { isConflict() }
            }
    }

    @Test
    fun `concurrent reservation and schedule block should persist exactly one busy interval`() {
        val customer =
            userRepository.save(
                User(phoneNumber = "+48600600600", firstName = "Klient", lastName = "Testowy"),
            )
        val customerSession = testSessions.customer(customer)
        val offering =
            serviceRepository.save(
                Offering(companyId = companyId, name = "Kolizja", durationMinutes = 60, price = 100),
            )
        val startTime = testDate.atTime(12, 0)
        val endTime = startTime.plusHours(1)
        val reservationBody =
            mapOf(
                "employeeId" to employee.id!!,
                "serviceId" to offering.id!!,
                "startTime" to startTime.toString(),
                "firstName" to "Klient",
                "lastName" to "Testowy",
            )
        val blockBody =
            mapOf(
                "employeeId" to employee.id!!,
                "startTime" to startTime.toString(),
                "endTime" to endTime.toString(),
            )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val reservationRequest =
                executor.submit<Int> {
                    start.await()
                    mockMvc
                        .post("/api/reservations") {
                            with(csrf())
                            header("Idempotency-Key", "booking-block-race")
                            cookie(customerSession)
                            contentType = MediaType.APPLICATION_JSON
                            content = objectMapper.writeValueAsString(reservationBody)
                        }
                        .andReturn()
                        .response
                        .status
                }
            val blockRequest =
                executor.submit<Int> {
                    start.await()
                    mockMvc
                        .post("/api/schedule-blocks") {
                            with(csrf())
                            cookie(ownerSession)
                            contentType = MediaType.APPLICATION_JSON
                            content = objectMapper.writeValueAsString(blockBody)
                        }
                        .andReturn()
                        .response
                        .status
                }

            start.countDown()
            val statuses =
                listOf(
                        reservationRequest.get(10, TimeUnit.SECONDS),
                        blockRequest.get(10, TimeUnit.SECONDS),
                    )
                    .sorted()

            assertEquals(listOf(201, 409), statuses)
            assertEquals(1L, reservationRepository.count() + scheduleBlockRepository.count())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `DELETE schedule-blocks should remove block and return 204`() {
        val block =
            scheduleBlockRepository.save(
                ScheduleBlock(
                    companyId = companyId,
                    employeeId = employee.id!!,
                    startTime = testDate.atTime(14, 0),
                    endTime = testDate.atTime(15, 0),
                ),
            )

        mockMvc
            .delete("/api/schedule-blocks/${block.id!!}") {
                with(csrf())
                cookie(employeeSession)
            }
            .andExpect {
                status { isNoContent() }
            }

        assertTrue(scheduleBlockRepository.findAll().isEmpty())
    }

    @Test
    fun `DELETE schedule-blocks should return 403 when trying to delete another employee block`() {
        val otherEmployee =
            userRepository.save(
                User(phoneNumber = "+48600100200", firstName = "Inny", lastName = "Pracownik"),
            )
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = companyId, userId = otherEmployee.id!!, role = "EMPLOYEE"),
        )
        val block =
            scheduleBlockRepository.save(
                ScheduleBlock(
                    companyId = companyId,
                    employeeId = otherEmployee.id!!,
                    startTime = testDate.atTime(10, 0),
                    endTime = testDate.atTime(11, 0),
                ),
            )

        mockMvc
            .delete("/api/schedule-blocks/${block.id!!}") {
                with(csrf())
                cookie(employeeSession)
            }
            .andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `DELETE schedule-blocks employee cannot delete own block from another company`() {
        val otherCompany = companyRepository.save(Company(name = "Inny Salon"))
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = otherCompany.id!!, userId = employee.id!!, role = "EMPLOYEE"),
        )
        val block =
            scheduleBlockRepository.save(
                ScheduleBlock(
                    companyId = otherCompany.id!!,
                    employeeId = employee.id!!,
                    startTime = testDate.atTime(10, 0),
                    endTime = testDate.atTime(11, 0),
                ),
            )

        mockMvc
            .delete("/api/schedule-blocks/${block.id!!}") {
                with(csrf())
                cookie(employeeSession)
            }
            .andExpect {
                status { isNotFound() }
            }

        assertTrue(scheduleBlockRepository.existsById(block.id!!))
    }

    @Test
    fun `GET schedule-blocks employee should return blocks in range`() {
        scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employee.id!!,
                startTime = testDate.atTime(9, 0),
                endTime = testDate.atTime(10, 0),
                reason = "Spotkanie",
            ),
        )
        scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employee.id!!,
                startTime = testDate.atTime(14, 0),
                endTime = testDate.atTime(15, 0),
                reason = "Szkolenie",
            ),
        )

        val start = testDate.atStartOfDay()
        val end = testDate.plusDays(1).atStartOfDay()

        mockMvc
            .get("/api/schedule-blocks/employee") {
                cookie(employeeSession)
                param("start", start.toString())
                param("end", end.toString())
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
            }
    }

    @Test
    fun `GET schedule-blocks employee should include blocks overlapping range start`() {
        scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employee.id!!,
                startTime = testDate.atStartOfDay().minusMinutes(30),
                endTime = testDate.atStartOfDay().plusMinutes(30),
                reason = "Dyżur",
            ),
        )

        mockMvc
            .get("/api/schedule-blocks/employee") {
                cookie(employeeSession)
                param("start", testDate.atStartOfDay().toString())
                param("end", testDate.atTime(1, 0).toString())
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].reason") { value("Dyżur") }
            }
    }

    @Test
    fun `schedule block should make slot unavailable in availability endpoint`() {
        // Blokujemy pracownika od 10:00 do 11:00
        scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employee.id!!,
                startTime = testDate.atTime(10, 0),
                endTime = testDate.atTime(11, 0),
            ),
        )

        val service =
            serviceRepository.save(
                Offering(companyId = companyId, name = "Strzyżenie", durationMinutes = 60, price = 80),
            )

        mockMvc
            .get("/api/availability") {
                param("employeeId", employee.id!!.toString())
                param("serviceId", service.id!!.toString())
                param("date", testDate.toString())
            }
            .andExpect {
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
    fun `POST schedule-blocks should return 401 without session`() {
        val body =
            mapOf(
                "startTime" to testDate.atTime(10, 0).toString(),
                "endTime" to testDate.atTime(11, 0).toString(),
            )

        mockMvc
            .post("/api/schedule-blocks") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `POST schedule-blocks owner can create block for another employee by supplying employeeId`() {
        val body =
            mapOf(
                "startTime" to testDate.atTime(12, 0).toString(),
                "endTime" to testDate.atTime(13, 0).toString(),
                "reason" to "Przerwa pracownika",
                "employeeId" to employee.id!!,
            )

        mockMvc
            .post("/api/schedule-blocks") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }
            .andExpect {
                status { isCreated() }
            }

        val blocks = scheduleBlockRepository.findAll()
        assertEquals(1, blocks.size)
        assertEquals(employee.id!!, blocks[0].employeeId)
    }

    @Test
    fun `POST schedule-blocks owner receives 404 for employee from another company`() {
        val otherCompany = companyRepository.save(Company(name = "Obcy Salon POST"))
        val otherEmployee =
            userRepository.save(
                User(phoneNumber = "+48900100201", firstName = "Obcy", lastName = "Pracownik"),
            )
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = otherCompany.id!!, userId = otherEmployee.id!!, role = "EMPLOYEE"),
        )
        val body =
            mapOf(
                "startTime" to testDate.atTime(12, 0).toString(),
                "endTime" to testDate.atTime(13, 0).toString(),
                "employeeId" to otherEmployee.id!!,
            )

        mockMvc
            .post("/api/schedule-blocks") {
                with(csrf())
                cookie(ownerSession)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }
            .andExpect {
                status { isNotFound() }
            }

        assertTrue(scheduleBlockRepository.findAll().isEmpty())
    }

    @Test
    fun `GET schedule-blocks owner receives 404 for employee from another company`() {
        val otherCompany = companyRepository.save(Company(name = "Obcy Salon GET"))
        val otherEmployee =
            userRepository.save(
                User(phoneNumber = "+48900100202", firstName = "Obcy", lastName = "Pracownik"),
            )
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = otherCompany.id!!, userId = otherEmployee.id!!, role = "EMPLOYEE"),
        )

        mockMvc
            .get("/api/schedule-blocks/employee") {
                cookie(ownerSession)
                param("employeeId", otherEmployee.id!!.toString())
                param("start", testDate.atStartOfDay().toString())
                param("end", testDate.plusDays(1).atStartOfDay().toString())
            }
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `blocks for shared employee are isolated between companies in reads and availability`() {
        val otherCompany = companyRepository.save(Company(name = "Drugi Salon"))
        val otherCompanyId = otherCompany.id!!
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = otherCompanyId, userId = employee.id!!, role = "EMPLOYEE"),
        )
        val otherOwnerEmployment =
            companyEmployeeRepository.save(
                CompanyEmployee(companyId = otherCompanyId, userId = owner.id!!, role = "OWNER"),
            )
        val otherCompanyOwnerSession = testSessions.staff(owner, otherOwnerEmployment)
        scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employee.id!!,
                startTime = testDate.atTime(10, 0),
                endTime = testDate.atTime(11, 0),
            ),
        )
        val otherOffering =
            serviceRepository.save(
                Offering(companyId = otherCompanyId, name = "Usługa drugiego salonu", durationMinutes = 60, price = 90),
            )
        workScheduleRepository.save(
            EmployeeWorkSchedule(
                companyId = otherCompanyId,
                employeeId = employee.id!!,
                dayOfWeek = testDate.dayOfWeek,
                startTime = LocalTime.of(9, 0),
                endTime = LocalTime.of(17, 0),
            ),
        )

        mockMvc
            .get("/api/schedule-blocks/employee") {
                cookie(otherCompanyOwnerSession)
                param("employeeId", employee.id!!.toString())
                param("start", testDate.atStartOfDay().toString())
                param("end", testDate.plusDays(1).atStartOfDay().toString())
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }

        mockMvc
            .get("/api/availability") {
                param("employeeId", employee.id!!.toString())
                param("serviceId", otherOffering.id!!.toString())
                param("date", testDate.toString())
            }
            .andExpect {
                status { isOk() }
                jsonPath("$[?(@.time == '10:00:00')]") { exists() }
            }
    }

    @Test
    fun `database rejects schedule block for employee outside company`() {
        val outsider =
            userRepository.save(
                User(phoneNumber = "+48900100203", firstName = "Bez", lastName = "Firmy"),
            )

        assertThrows<DataIntegrityViolationException> {
            scheduleBlockRepository.saveAndFlush(
                ScheduleBlock(
                    companyId = companyId,
                    employeeId = outsider.id!!,
                    startTime = testDate.atTime(10, 0),
                    endTime = testDate.atTime(11, 0),
                ),
            )
        }
    }

    @Test
    fun `database allows overlapping blocks for shared employee in different companies`() {
        val otherCompany = companyRepository.save(Company(name = "Salon współdzielony"))
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = otherCompany.id!!, userId = employee.id!!, role = "EMPLOYEE"),
        )
        scheduleBlockRepository.saveAndFlush(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employee.id!!,
                startTime = testDate.atTime(10, 0),
                endTime = testDate.atTime(11, 0),
            ),
        )

        scheduleBlockRepository.saveAndFlush(
            ScheduleBlock(
                companyId = otherCompany.id!!,
                employeeId = employee.id!!,
                startTime = testDate.atTime(10, 0),
                endTime = testDate.atTime(11, 0),
            ),
        )

        assertEquals(2, scheduleBlockRepository.findAll().size)
    }

    @Test
    fun `DELETE schedule-blocks owner can delete another employee block in their company`() {
        val block =
            scheduleBlockRepository.save(
                ScheduleBlock(
                    companyId = companyId,
                    employeeId = employee.id!!,
                    startTime = testDate.atTime(14, 0),
                    endTime = testDate.atTime(15, 0),
                ),
            )

        mockMvc
            .delete("/api/schedule-blocks/${block.id!!}") {
                with(csrf())
                cookie(ownerSession)
            }
            .andExpect {
                status { isNoContent() }
            }

        assertTrue(scheduleBlockRepository.findAll().isEmpty())
    }

    @Test
    fun `DELETE schedule-blocks owner cannot delete block from another company`() {
        val otherCompany = companyRepository.save(Company(name = "Inny Salon"))
        val otherEmployee =
            userRepository.save(User(phoneNumber = "+48999888777", firstName = "Obcy", lastName = "Pracownik"))
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = otherCompany.id!!, userId = otherEmployee.id!!, role = "EMPLOYEE"),
        )
        val block =
            scheduleBlockRepository.save(
                ScheduleBlock(
                    companyId = otherCompany.id!!,
                    employeeId = otherEmployee.id!!,
                    startTime = testDate.atTime(10, 0),
                    endTime = testDate.atTime(11, 0),
                ),
            )

        mockMvc
            .delete("/api/schedule-blocks/${block.id!!}") {
                with(csrf())
                cookie(ownerSession)
            }
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `employee explicit coworker target is rejected and coworker block reasons are readable`() {
        val other = userRepository.save(User(phoneNumber = "+48555444333", firstName = "Inny", lastName = "Pracownik"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = other.id!!, role = "EMPLOYEE"))
        val start = testDate.atTime(9, 0)
        val end = testDate.atTime(10, 0)
        mockMvc
            .post("/api/schedule-blocks") {
                with(csrf())
                cookie(employeeSession)
                contentType = MediaType.APPLICATION_JSON
                content =
                    objectMapper.writeValueAsString(
                        mapOf("employeeId" to other.id!!, "startTime" to start.toString(), "endTime" to end.toString()),
                    )
            }
            .andExpect { status { isForbidden() } }
        assertEquals(0, scheduleBlockRepository.count())
        scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = other.id!!,
                startTime = start,
                endTime = end,
                reason = "Urlop",
            ),
        )
        mockMvc
            .get("/api/schedule-blocks/employee") {
                cookie(employeeSession)
                param("employeeId", other.id!!.toString())
                param("start", start.minusDays(1).toString())
                param("end", end.plusDays(1).toString())
            }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].reason") { value("Urlop") }
                jsonPath("$.length()") { value(1) }
            }
    }

    @Test
    fun `multiday block may span DST while ambiguous endpoints are rejected`() {
        mockMvc
            .post("/api/schedule-blocks") {
                with(csrf())
                cookie(employeeSession)
                contentType = MediaType.APPLICATION_JSON
                content = """{"startTime":"2030-03-30T12:00:00","endTime":"2030-04-01T12:00:00"}"""
            }
            .andExpect { status { isCreated() } }
        mockMvc
            .post("/api/schedule-blocks") {
                with(csrf())
                cookie(employeeSession)
                contentType = MediaType.APPLICATION_JSON
                content = """{"startTime":"2030-10-27T02:15:00","endTime":"2030-10-27T04:00:00"}"""
            }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_LOCAL_TIME") }
            }
    }

    private fun savedBlock(employeeId: UUID = employee.id!!, startHour: Int = 10): ScheduleBlock =
        scheduleBlockRepository.saveAndFlush(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employeeId,
                startTime = testDate.atTime(startHour, 0),
                endTime = testDate.atTime(startHour + 1, 0),
                reason = "Przerwa",
            ),
        )

    private fun moveBlock(
        block: ScheduleBlock,
        start: LocalDateTime = testDate.atTime(10, 15),
        end: LocalDateTime = start.plusHours(1),
        session: Cookie = employeeSession,
        revision: String? = block.version.toString(),
    ): ResultActionsDsl =
        mockMvc.put("/api/schedule-blocks/${block.id}") {
            with(csrf())
            cookie(session)
            if (revision != null) header("If-Match", revision)
            contentType = MediaType.APPLICATION_JSON
            content =
                objectMapper.writeValueAsString(mapOf("startTime" to start.toString(), "endTime" to end.toString()))
        }

    @Test
    fun `overlap query excludes only the requested block and includes all blocks by default`() {
        val editedBlock = savedBlock()
        val otherBlock = savedBlock(startHour = 12)
        val start = testDate.atTime(10, 0)
        val end = testDate.atTime(13, 0)

        assertEquals(
            listOf(editedBlock.id, otherBlock.id),
            scheduleBlockRepository.findOverlapping(companyId, employee.id!!, start, end).map { it.id },
        )
        assertEquals(
            listOf(otherBlock.id),
            scheduleBlockRepository.findOverlapping(companyId, employee.id!!, start, end, editedBlock.id).map { it.id },
        )
    }

    @Test
    fun `PUT moves a block across its old interval preserving identity and returns new revision`() {
        val block = savedBlock()
        val createdAt = scheduleBlockRepository.findById(block.id!!).orElseThrow().createdAt
        moveBlock(block, revision = "\"0\"").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(block.id.toString()) }
            jsonPath("$.reason") { value("Przerwa") }
            jsonPath("$.revision") { value(1) }
        }
        val updated = scheduleBlockRepository.findById(block.id!!).orElseThrow()
        assertEquals(testDate.atTime(10, 15), updated.startTime)
        assertEquals(testDate.atTime(11, 15), updated.endTime)
        assertEquals(block.employeeId, updated.employeeId)
        assertEquals(createdAt, updated.createdAt)
        assertEquals(1L, scheduleBlockRepository.count())
        moveBlock(block, start = testDate.atTime(12, 0)).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CONCURRENT_MODIFICATION") }
        }
        assertEquals(updated.startTime, scheduleBlockRepository.findById(block.id!!).orElseThrow().startTime)
    }

    @Test
    fun `PUT rejects overlapping block atomically but allows adjacent interval`() {
        val block = savedBlock()
        savedBlock(startHour = 12)
        moveBlock(block, testDate.atTime(11, 15)).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("SLOT_UNAVAILABLE") }
        }
        assertEquals(block.startTime, scheduleBlockRepository.findById(block.id!!).orElseThrow().startTime)
        moveBlock(block, testDate.atTime(11, 0)).andExpect { status { isOk() } }
    }

    @Test
    fun `PUT rejects occupied reservation interval`() {
        val block = savedBlock()
        val offering =
            serviceRepository.save(Offering(companyId = companyId, name = "Test", durationMinutes = 60, price = 100))
        reservationRepository.saveAndFlush(
            Reservation(
                companyId = companyId,
                employeeId = employee.id!!,
                customerId = owner.id!!,
                serviceId = offering.id!!,
                price = 100,
                startTime = testDate.atTime(12, 0),
                endTime = testDate.atTime(13, 0),
            ),
        )
        moveBlock(block, testDate.atTime(12, 0)).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("SLOT_UNAVAILABLE") }
        }
    }

    @Test
    fun `PUT employee cannot move colleague block but owner can`() {
        val block = savedBlock(employeeId = owner.id!!)
        moveBlock(block).andExpect { status { isForbidden() } }
        moveBlock(block, session = ownerSession).andExpect { status { isOk() } }
    }

    @Test
    fun `PUT hides blocks belonging to another company from employee and owner`() {
        val otherCompany = companyRepository.save(Company(name = "Inna firma"))
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = otherCompany.id!!, userId = employee.id!!, role = "EMPLOYEE"),
        )
        val block =
            scheduleBlockRepository.saveAndFlush(
                ScheduleBlock(
                    companyId = otherCompany.id!!,
                    employeeId = employee.id!!,
                    startTime = testDate.atTime(10, 0),
                    endTime = testDate.atTime(11, 0),
                ),
            )
        for (session in listOf(employeeSession, ownerSession)) {
            moveBlock(block, session = session).andExpect { status { isNotFound() } }
        }
    }

    @Test
    fun `PUT requires valid revision CSRF and interval`() {
        val block = savedBlock()
        for (revision in listOf(null, "invalid", "-1")) {
            moveBlock(block, revision = revision).andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("REVISION_REQUIRED") }
            }
        }
        moveBlock(block, end = testDate.atTime(10, 15)).andExpect { status { isBadRequest() } }
        mockMvc
            .put("/api/schedule-blocks/${block.id}") {
                cookie(employeeSession)
                header("If-Match", "0")
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }
            .andExpect { status { isForbidden() } }
        mockMvc
            .put("/api/schedule-blocks/${block.id}") {
                with(csrf())
                cookie(employeeSession)
                header("If-Match", "0")
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `PUT rejects ambiguous or nonexistent Warsaw endpoints`() {
        val block = savedBlock()
        for (day in listOf(LocalDate.of(2030, 3, 31), LocalDate.of(2030, 10, 27))) {
            moveBlock(block, start = day.atTime(2, 15), end = day.atTime(3, 30)).andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_LOCAL_TIME") }
            }
        }
    }

    @Test
    fun `concurrent PUT with the same revision accepts exactly one change`() {
        val block = savedBlock()
        val ready = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val changes =
                listOf(14, 16).map { hour ->
                    executor.submit<Int> {
                        ready.await()
                        moveBlock(block, start = testDate.atTime(hour, 0)).andReturn().response.status
                    }
                }
            ready.countDown()
            assertEquals(listOf(200, 409), changes.map { it.get(10, TimeUnit.SECONDS) }.sorted())
            assertEquals(1L, scheduleBlockRepository.findById(block.id!!).orElseThrow().version)
        } finally {
            executor.shutdownNow()
        }
    }
}
