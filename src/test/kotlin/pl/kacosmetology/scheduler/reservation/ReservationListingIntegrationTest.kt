package pl.kacosmetology.scheduler.reservation

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ReservationListingIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var companyRepository: CompanyRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @Autowired
    private lateinit var serviceRepository: OfferingRepository

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var jwtService: JwtService

    @MockkBean
    private lateinit var s3Client: S3Client

    private lateinit var customerToken: String
    private lateinit var employeeToken: String
    private lateinit var todayStart: LocalDateTime
    private lateinit var todayEnd: LocalDateTime

    @BeforeEach
    fun setup() {
        // 1. RĘCZNE CZYSZCZENIE BAZY (Od dzieci do rodziców)
        reservationRepository.deleteAll()
        serviceRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        // 2. Tworzymy firmę
        val company = companyRepository.save(Company(name = "Salon Piękności"))

        // 3. Tworzymy użytkowników: Pracownika i dwóch Klientów
        val employee =
            userRepository.save(User(phoneNumber = "+48111000111", firstName = "Anna", lastName = "Pracownicza"))
        val customerA = userRepository.save(User(phoneNumber = "+48222000222", firstName = "Jan", lastName = "KlientA"))
        val customerB =
            userRepository.save(User(phoneNumber = "+48333000333", firstName = "Piotr", lastName = "KlientB"))

        // Nadajemy Pracownikowi odpowiednią rolę w bazie
        val employeeEmployment = companyEmployeeRepository.save(
            CompanyEmployee(
                companyId = company.id!!,
                userId = employee.id,
                role = "EMPLOYEE"
            )
        )

        employeeToken = jwtService.generateStaffToken(employee, employeeEmployment)
        customerToken = jwtService.generateCustomerToken(customerA)

        // 4. Tworzymy usługę
        val service = serviceRepository.save(
            Offering(
                companyId = company.id!!,
                name = "Strzyżenie",
                durationMinutes = 60,
                price = 100
            )
        )

        // 5. Tworzymy Rezerwacje na dzisiaj
        todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0)
        todayEnd = LocalDateTime.now().withHour(23).withMinute(59).withSecond(59).withNano(999999999)

        // Rezerwacja 1: Klient A na 10:00 (Najwcześniejsza)
        reservationRepository.save(
            Reservation(
                companyId = company.id!!,
                customerId = customerA.id,
                employeeId = employee.id,
                serviceId = service.id!!,
                price = 100,
                startTime = todayStart.withHour(10),
                endTime = todayStart.withHour(11),
                status = ReservationStatus.CONFIRMED
            )
        )

        // Rezerwacja 2: Klient A na 12:00 (Późniejsza)
        reservationRepository.save(
            Reservation(
                companyId = company.id!!,
                customerId = customerA.id,
                employeeId = employee.id,
                serviceId = service.id!!,
                price = 100,
                startTime = todayStart.withHour(12),
                endTime = todayStart.withHour(13),
                status = ReservationStatus.CONFIRMED
            )
        )

        // Rezerwacja 3: Klient B na 14:00 (Cudza rezerwacja)
        reservationRepository.save(
            Reservation(
                companyId = company.id!!,
                customerId = customerB.id,
                employeeId = employee.id,
                serviceId = service.id!!,
                price = 100,
                startTime = todayStart.withHour(14),
                endTime = todayStart.withHour(15),
                status = ReservationStatus.CONFIRMED
            )
        )
    }

    @Test
    fun `customer should see only own reservations sorted descending by newest (200)`() {
        mockMvc.get("/api/reservations/me") {
            header("Authorization", "Bearer $customerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }

            // Ponieważ użyliśmy OrderByStartTimeDesc, wizyta z 12:00 (Późniejsza) powinna być pierwsza na liście [0]
            // Uwaga: używamy substring(0, 19) w teście, by zignorować ułamki sekund dodawane przez bazę/Javę
            jsonPath("$[0].startTime") {
                value(
                    org.hamcrest.Matchers.startsWith(
                        todayStart.withHour(12).toString().substring(0, 16)
                    )
                )
            }
            jsonPath("$[1].startTime") {
                value(
                    org.hamcrest.Matchers.startsWith(
                        todayStart.withHour(10).toString().substring(0, 16)
                    )
                )
            }
        }
    }

    @Test
    fun `employee should see full schedule for a given day sorted ascending (200)`() {
        mockMvc.get("/api/reservations/employee") {
            header("Authorization", "Bearer $employeeToken")
            param("start", todayStart.toString())
            param("end", todayEnd.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(3) }

            // W grafiku pracownika ma być chronologicznie (OrderByStartTimeAsc)
            jsonPath("$[0].startTime") {
                value(
                    org.hamcrest.Matchers.startsWith(
                        todayStart.withHour(10).toString().substring(0, 16)
                    )
                )
            }
            jsonPath("$[1].startTime") {
                value(
                    org.hamcrest.Matchers.startsWith(
                        todayStart.withHour(12).toString().substring(0, 16)
                    )
                )
            }
            jsonPath("$[2].startTime") {
                value(
                    org.hamcrest.Matchers.startsWith(
                        todayStart.withHour(14).toString().substring(0, 16)
                    )
                )
            }
        }
    }

    @Test
    fun `employee schedule should include reservations overlapping range start`() {
        val employee = userRepository.findAll().first { it.phoneNumber == "+48111000111" }
        val customer = userRepository.findAll().first { it.phoneNumber == "+48222000222" }
        val service = serviceRepository.findAll().first()
        reservationRepository.save(
            Reservation(
                companyId = service.companyId,
                customerId = customer.id,
                employeeId = employee.id,
                serviceId = service.id!!,
                price = 100,
                startTime = todayStart.minusMinutes(30),
                endTime = todayStart.plusMinutes(30),
                status = ReservationStatus.CONFIRMED
            )
        )

        mockMvc.get("/api/reservations/employee") {
            header("Authorization", "Bearer $employeeToken")
            param("start", todayStart.toString())
            param("end", todayStart.plusHours(1).toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].startTime") {
                value(
                    org.hamcrest.Matchers.startsWith(
                        todayStart.minusMinutes(30).toString().substring(0, 16)
                    )
                )
            }
        }
    }

    @Test
    fun `customer cannot access employee schedule (403)`() {
        mockMvc.get("/api/reservations/employee") {
            header("Authorization", "Bearer $customerToken")
            param("start", todayStart.toString())
            param("end", todayEnd.toString())
        }.andExpect {
            status { isForbidden() }
        }
    }
}
