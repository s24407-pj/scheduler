package pl.kacosmetology.scheduler.reservation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import jakarta.servlet.http.Cookie
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.dto.CreateReservationRequest
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlock
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlockRepository
import pl.kacosmetology.scheduler.security.TestSessions
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkSchedule
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkScheduleRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CustomerReservationFlowIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var companyRepository: CompanyRepository

    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired private lateinit var serviceRepository: OfferingRepository

    @Autowired private lateinit var redisTemplate: StringRedisTemplate

    @Autowired private lateinit var reservationRepository: ReservationRepository

    @Autowired private lateinit var scheduleBlockRepository: ScheduleBlockRepository

    @Autowired private lateinit var testSessions: TestSessions

    @MockkBean private lateinit var smsSender: SmsSender

    @Autowired private lateinit var workScheduleRepository: EmployeeWorkScheduleRepository

    @MockkBean private lateinit var s3Client: S3Client

    private var companyId: Long = 0
    private var employeeId: Long = 0
    private var serviceId: Long = 0

    @BeforeEach
    fun setup() {
        // 1. Czyszczenie bazy danych przed testem
        reservationRepository.deleteAll()
        scheduleBlockRepository.deleteAll()
        workScheduleRepository.deleteAll()
        redisTemplate.connectionFactory?.connection?.serverCommands()?.flushAll()
        serviceRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        // 2. Mockowanie wysyłki SMS
        every { smsSender.sendOtp(any(), any()) } just Runs

        // 3. Konfiguracja Salonu i Pracownika
        val company = companyRepository.save(Company(name = "Salon Piękności"))
        val employee =
            userRepository.save(User(phoneNumber = "+48999000111", firstName = "Pani", lastName = "Kosmetyczka"))
        companyEmployeeRepository.save(
            CompanyEmployee(
                companyId = company.id!!,
                userId = employee.id!!,
                role = "EMPLOYEE",
            ),
        )

        val service =
            serviceRepository.save(
                Offering(
                    companyId = company.id!!,
                    name = "Manicure",
                    durationMinutes = 60,
                    price = 120,
                ),
            )

        companyId = company.id!!
        employeeId = employee.id!!
        serviceId = service.id!!
        saveWeeklySchedule(companyId, employeeId)
    }

    private fun saveWeeklySchedule(companyId: Long, employeeId: Long) {
        DayOfWeek.entries.forEach { day ->
            workScheduleRepository.save(
                EmployeeWorkSchedule(
                    companyId = companyId,
                    employeeId = employeeId,
                    dayOfWeek = day,
                    startTime = LocalTime.of(8, 0),
                    endTime = LocalTime.of(18, 0),
                ),
            )
        }
    }

    private fun book(session: Cookie, startTime: LocalDateTime, offeringId: Long = serviceId) =
        mockMvc.post("/api/reservations") {
            with(csrf())
            cookie(session)
            contentType = MediaType.APPLICATION_JSON
            content =
                objectMapper.writeValueAsString(
                    CreateReservationRequest(employeeId = employeeId, serviceId = offeringId, startTime = startTime),
                )
        }

    @Test
    fun `customer booking off the slot grid should return 409`() {
        book(customerSession("+48555700001"), tomorrowAt(10).plusMinutes(7)).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("SLOT_UNAVAILABLE") }
            jsonPath("$.detail") { value("Ten termin nie jest dostępny do rezerwacji") }
        }

        assertEquals(0, reservationRepository.findAll().size)
    }

    @Test
    fun `customer booking outside the employee's working hours should return 409`() {
        val session = customerSession("+48555700002")

        book(session, tomorrowAt(7)).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("SLOT_UNAVAILABLE") }
        }
        // 60-minute offering starting 17:30 would end after 18:00
        book(session, tomorrowAt(17).plusMinutes(30)).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("SLOT_UNAVAILABLE") }
        }

        assertEquals(0, reservationRepository.findAll().size)
    }

    @Test
    fun `customer can cancel before the visit starts but not after`() {
        val customer = userRepository.save(User(phoneNumber = "+48555700003", firstName = "K", lastName = "T"))
        val session = testSessions.customer(customer)
        val future = saveReservation(customer.id!!, LocalDateTime.now(BUSINESS_ZONE).plusHours(1))
        val started = saveReservation(customer.id!!, LocalDateTime.now(BUSINESS_ZONE).minusMinutes(90))

        mockMvc
            .patch("/api/reservations/${future.id!!}/cancel") {
                with(csrf())
                cookie(session)
            }
            .andExpect {
                status { isNoContent() }
            }
        mockMvc
            .patch("/api/reservations/${started.id!!}/cancel") {
                with(csrf())
                cookie(session)
            }
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("INVALID_STATUS_TRANSITION") }
            }

        assertEquals(ReservationStatus.CANCELLED, reservationRepository.findById(future.id!!).get().status)
        assertEquals(ReservationStatus.BOOKED, reservationRepository.findById(started.id!!).get().status)
    }

    @Test
    fun `employee shared by two companies can be booked at the same time at each company`() {
        val otherCompany = companyRepository.save(Company(name = "Drugi Salon"))
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = otherCompany.id!!, userId = employeeId, role = "EMPLOYEE"),
        )
        val otherOffering =
            serviceRepository.save(
                Offering(companyId = otherCompany.id!!, name = "Pedicure", durationMinutes = 60, price = 90),
            )
        saveWeeklySchedule(otherCompany.id!!, employeeId)

        book(customerSession("+48555700004"), tomorrowAt(10)).andExpect { status { isCreated() } }

        mockMvc
            .get("/api/availability") {
                param("employeeId", employeeId.toString())
                param("serviceId", otherOffering.id!!.toString())
                param("date", tomorrowAt(10).toLocalDate().toString())
            }
            .andExpect {
                status { isOk() }
                jsonPath("$[?(@.time == '10:00:00')]") { exists() }
            }
        book(customerSession("+48555700005"), tomorrowAt(10), otherOffering.id!!).andExpect {
            status { isCreated() }
        }

        assertEquals(2, reservationRepository.findAll().size)
    }

    private fun saveReservation(customerId: Long, startTime: LocalDateTime): Reservation =
        reservationRepository.save(
            Reservation(
                companyId = companyId,
                customerId = customerId,
                employeeId = employeeId,
                serviceId = serviceId,
                price = 120,
                startTime = startTime,
                endTime = startTime.plusMinutes(60),
            ),
        )

    @Test
    fun `full flow - new customer registers via SMS and creates a reservation`() {
        val newCustomerPhone = "+48555666777"

        // ==========================================
        // KROK 1: Klient prosi o kod SMS
        // ==========================================
        val requestCodeReq = RequestCodeRequest(phoneNumber = newCustomerPhone)

        mockMvc
            .post("/api/auth/request-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(requestCodeReq)
            }
            .andExpect {
                status { isOk() }
            }

        // Odczytujemy kod z Redis
        val generatedCode = redisTemplate.opsForValue().get("otp:$newCustomerPhone")
        assertNotNull(generatedCode, "Kod OTP powinien być w Redis")

        // ==========================================
        // KROK 2: Klient weryfikuje kod i podaje dane
        // ==========================================
        val verifyCodeReq =
            VerifyCodeRequest(
                phoneNumber = newCustomerPhone,
                code = generatedCode!!,
                firstName = "Julia",
                lastName = "Nowak",
            )

        // The credential is supplied in the HttpOnly session cookie.
        val verifyResponse =
            mockMvc
                .post("/api/auth/verify-code") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(verifyCodeReq)
                }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.token") { doesNotExist() }
                    cookie { exists("SESSION") }
                }
                .andReturn()

        // Retain the cookie for subsequent requests.
        val sessionCookie = requireNotNull(verifyResponse.response.getCookie("SESSION"))

        // ==========================================
        // KROK 3: Klient tworzy rezerwację z użyciem session
        // ==========================================
        // Wybieramy datę w przyszłości, żeby przejść przez Twoją nową walidację @Future!
        val reservationTime = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0)

        val createReservationReq =
            CreateReservationRequest(
                employeeId = employeeId,
                serviceId = serviceId,
                startTime = reservationTime,
            )

        mockMvc
            .post("/api/reservations") {
                with(csrf())
                cookie(sessionCookie) // Reuse the authenticated session cookie.
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(createReservationReq)
            }
            .andExpect {
                status { isCreated() } // Oczekujemy 201 Created
            }

        // ==========================================
        // KROK 4: Weryfikacja efektu końcowego w bazie
        // ==========================================
        val reservationsInDb = reservationRepository.findAll()
        assertEquals(1, reservationsInDb.size, "Powinna powstać dokładnie jedna rezerwacja")

        val savedReservation = reservationsInDb.first()
        assertEquals(employeeId, savedReservation.employeeId)
        assertEquals(serviceId, savedReservation.serviceId)
        assertEquals(120, savedReservation.price, "Cena powinna być skopiowana ze snapshotu usługi")
        assertEquals(ReservationStatus.BOOKED, savedReservation.status)

        // Weryfikacja czy nowo zarejestrowany klient jest poprawnie przypisany
        val customerInDb = userRepository.findByPhoneNumber(newCustomerPhone)
        assertNotNull(customerInDb)
        assertEquals(customerInDb!!.id!!, savedReservation.customerId, "Rezerwacja powinna należeć do nowego klienta")
    }

    @Test
    fun `customer reservation should return 409 when requested time overlaps schedule block`() {
        val customerPhone = "+48555111222"
        val requestCodeReq = RequestCodeRequest(phoneNumber = customerPhone)

        mockMvc
            .post("/api/auth/request-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(requestCodeReq)
            }
            .andExpect {
                status { isOk() }
            }

        val generatedCode = redisTemplate.opsForValue().get("otp:$customerPhone")
        assertNotNull(generatedCode)

        val verifyResponse =
            mockMvc
                .post("/api/auth/verify-code") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        objectMapper.writeValueAsString(
                            VerifyCodeRequest(
                                phoneNumber = customerPhone,
                                code = generatedCode!!,
                                firstName = "Adam",
                                lastName = "Testowy",
                            ),
                        )
                }
                .andExpect {
                    status { isOk() }
                }
                .andReturn()

        val sessionCookie = requireNotNull(verifyResponse.response.getCookie("SESSION"))
        val blockStart = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0)
        scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyRepository.findAll().first().id!!,
                employeeId = employeeId,
                startTime = blockStart,
                endTime = blockStart.plusHours(1),
            ),
        )

        val createReservationReq =
            CreateReservationRequest(
                employeeId = employeeId,
                serviceId = serviceId,
                startTime = blockStart.plusMinutes(30),
            )

        mockMvc
            .post("/api/reservations") {
                with(csrf())
                cookie(sessionCookie)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(createReservationReq)
            }
            .andExpect {
                status { isConflict() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.status") { value(409) }
                jsonPath("$.code") { value("SLOT_UNAVAILABLE") }
                jsonPath("$.detail") { value("Ten termin jest już zajęty") }
                jsonPath("$.instance") { value("/api/reservations") }
            }

        assertEquals(0, reservationRepository.findAll().size)
    }

    @Test
    fun `repeating an identical booking request should return the existing reservation with 200`() {
        val session = customerSession("+48555333444")
        val body =
            objectMapper.writeValueAsString(
                CreateReservationRequest(employeeId = employeeId, serviceId = serviceId, startTime = tomorrowAt(11)),
            )

        val firstId =
            mockMvc
                .post("/api/reservations") {
                    with(csrf())
                    cookie(session)
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
                .andExpect {
                    status { isCreated() }
                }
                .andReturn()
                .let { objectMapper.readTree(it.response.contentAsString).get("id").asLong() }

        mockMvc
            .post("/api/reservations") {
                with(csrf())
                cookie(session)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(firstId) }
            }

        assertEquals(1, reservationRepository.findAll().size)
    }

    @Test
    fun `booking a local time skipped by the daylight saving change should return 400`() {
        val session = customerSession("+48555333555")
        val transition =
            generateSequence(BUSINESS_ZONE.rules.nextTransition(Instant.now())) {
                    BUSINESS_ZONE.rules.nextTransition(it.instant)
                }
                .first { it.isGap }

        mockMvc
            .post("/api/reservations") {
                with(csrf())
                cookie(session)
                contentType = MediaType.APPLICATION_JSON
                content =
                    objectMapper.writeValueAsString(
                        CreateReservationRequest(
                            employeeId = employeeId,
                            serviceId = serviceId,
                            startTime = transition.dateTimeBefore.plusMinutes(30),
                        ),
                    )
            }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_LOCAL_TIME") }
            }

        assertEquals(0, reservationRepository.findAll().size)
    }

    @Test
    fun `cancelling another customer's reservation should return 404`() {
        val ownerSession = customerSession("+48555333666")
        val reservationId =
            mockMvc
                .post("/api/reservations") {
                    with(csrf())
                    cookie(ownerSession)
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        objectMapper.writeValueAsString(
                            CreateReservationRequest(
                                employeeId = employeeId,
                                serviceId = serviceId,
                                startTime = tomorrowAt(12),
                            ),
                        )
                }
                .andExpect {
                    status { isCreated() }
                }
                .andReturn()
                .let { objectMapper.readTree(it.response.contentAsString).get("id").asLong() }

        mockMvc
            .patch("/api/reservations/$reservationId/cancel") {
                with(csrf())
                cookie(customerSession("+48555333777"))
            }
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("RESERVATION_NOT_FOUND") }
            }

        assertEquals(ReservationStatus.BOOKED, reservationRepository.findById(reservationId).get().status)
    }

    private fun customerSession(phone: String): Cookie =
        testSessions.customer(
            userRepository.save(User(phoneNumber = phone, firstName = "Klient", lastName = "Testowy")),
        )

    private fun tomorrowAt(hour: Int): LocalDateTime =
        LocalDateTime.now(BUSINESS_ZONE).plusDays(1).withHour(hour).withMinute(0).withSecond(0).withNano(0)

    @Test
    fun `customer reservation should return 400 when employee belongs to another company`() {
        val customerPhone = "+48555111999"
        val requestCodeReq = RequestCodeRequest(phoneNumber = customerPhone)

        mockMvc
            .post("/api/auth/request-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(requestCodeReq)
            }
            .andExpect {
                status { isOk() }
            }

        val generatedCode = redisTemplate.opsForValue().get("otp:$customerPhone")
        assertNotNull(generatedCode)

        val verifyResponse =
            mockMvc
                .post("/api/auth/verify-code") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        objectMapper.writeValueAsString(
                            VerifyCodeRequest(
                                phoneNumber = customerPhone,
                                code = generatedCode!!,
                                firstName = "Ewa",
                                lastName = "Testowa",
                            ),
                        )
                }
                .andExpect {
                    status { isOk() }
                }
                .andReturn()

        val sessionCookie = requireNotNull(verifyResponse.response.getCookie("SESSION"))
        val otherCompany = companyRepository.save(Company(name = "Obcy Salon"))
        val otherEmployee =
            userRepository.save(User(phoneNumber = "+48999123123", firstName = "Obcy", lastName = "Pracownik"))
        companyEmployeeRepository.save(
            CompanyEmployee(
                companyId = otherCompany.id!!,
                userId = otherEmployee.id!!,
                role = "EMPLOYEE",
            ),
        )
        val reservationTime = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0)

        val createReservationReq =
            CreateReservationRequest(
                employeeId = otherEmployee.id!!,
                serviceId = serviceId,
                startTime = reservationTime,
            )

        mockMvc
            .post("/api/reservations") {
                with(csrf())
                cookie(sessionCookie)
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(createReservationReq)
            }
            .andExpect {
                status { isBadRequest() }
            }

        assertEquals(0, reservationRepository.findAll().size)
    }
}
