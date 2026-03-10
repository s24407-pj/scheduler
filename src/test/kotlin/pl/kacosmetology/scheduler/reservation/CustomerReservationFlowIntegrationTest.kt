package pl.kacosmetology.scheduler.reservation

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.reservation.dto.CreateReservationRequest
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CustomerReservationFlowIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var companyRepository: CompanyRepository

    @Autowired
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired
    private lateinit var serviceRepository: OfferingRepository

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @MockkBean
    private lateinit var smsSender: SmsSender

    @MockkBean
    private lateinit var s3Client: S3Client

    private var employeeId: Long = 0
    private var serviceId: Long = 0

    @BeforeEach
    fun setup() {
        // 1. Czyszczenie bazy danych przed testem
        reservationRepository.deleteAll()
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
                userId = employee.id,
                role = "EMPLOYEE"
            )
        )

        val service = serviceRepository.save(
            Offering(
                companyId = company.id!!, name = "Manicure", durationMinutes = 60, price = 120
            )
        )

        employeeId = employee.id
        serviceId = service.id!!
    }

    @Test
    fun `full flow - new customer registers via SMS and creates a reservation`() {
        val newCustomerPhone = "+48555666777"

        // ==========================================
        // KROK 1: Klient prosi o kod SMS
        // ==========================================
        val requestCodeReq = RequestCodeRequest(phoneNumber = newCustomerPhone)

        mockMvc.post("/api/auth/request-code") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(requestCodeReq)
        }.andExpect {
            status { isOk() }
        }

        // Odczytujemy kod z Redis
        val generatedCode = redisTemplate.opsForValue().get("otp:$newCustomerPhone")
        assertNotNull(generatedCode, "Kod OTP powinien być w Redis")

        // ==========================================
        // KROK 2: Klient weryfikuje kod i podaje dane
        // ==========================================
        val verifyCodeReq = VerifyCodeRequest(
            phoneNumber = newCustomerPhone,
            code = generatedCode!!,
            firstName = "Julia",
            lastName = "Nowak"
        )

        // Wyciągamy token JWT z odpowiedzi JSON!
        val verifyResponse = mockMvc.post("/api/auth/verify-code") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(verifyCodeReq)
        }.andExpect {
            status { isOk() }
            jsonPath("$.token") { exists() }
        }.andReturn()

        // Parsujemy odpowiedź, aby zdobyć czysty string z tokenem
        val responseBody = verifyResponse.response.contentAsString
        val jwtToken = objectMapper.readTree(responseBody).get("token").stringValue()

        // ==========================================
        // KROK 3: Klient tworzy rezerwację z użyciem JWT
        // ==========================================
        // Wybieramy datę w przyszłości, żeby przejść przez Twoją nową walidację @Future!
        val reservationTime = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0)

        val createReservationReq = CreateReservationRequest(
            employeeId = employeeId,
            serviceId = serviceId,
            startTime = reservationTime
        )

        mockMvc.post("/api/reservations") {
            header("Authorization", "Bearer $jwtToken") // KLUCZOWE: Używamy zdobytego tokena!
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(createReservationReq)
        }.andExpect {
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
        assertEquals(ReservationStatus.PENDING, savedReservation.status)

        // Weryfikacja czy nowo zarejestrowany klient jest poprawnie przypisany
        val customerInDb = userRepository.findByPhoneNumber(newCustomerPhone)
        assertNotNull(customerInDb)
        assertEquals(customerInDb!!.id, savedReservation.customerId, "Rezerwacja powinna należeć do nowego klienta")
    }
}