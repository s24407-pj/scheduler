package pl.kacosmetology.scheduler.availability

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
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignment
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.reservation.ReservationStatus
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlock
import pl.kacosmetology.scheduler.scheduleblock.ScheduleBlockRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkSchedule
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkScheduleRepository
import software.amazon.awssdk.services.s3.S3Client
import java.time.LocalDate
import java.time.LocalTime

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AvailabilityIntegrationTest {

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
    private lateinit var scheduleBlockRepository: ScheduleBlockRepository

    @Autowired
    private lateinit var workScheduleRepository: EmployeeWorkScheduleRepository

    @Autowired
    private lateinit var assignmentRepository: EmployeeOfferingAssignmentRepository

    @MockkBean
    private lateinit var s3Client: S3Client

    private var employeeId: Long = 0
    private var serviceId: Long = 0
    private var companyId: Long = 0
    private val testDate = LocalDate.now().plusDays(2) // Za dwa dni, by uniknąć filtrów przeszłości

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        scheduleBlockRepository.deleteAll()
        assignmentRepository.deleteAll()
        workScheduleRepository.deleteAll()
        serviceRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Salon Włosów"))
        companyId = company.id!!
        val employee =
            userRepository.save(User(phoneNumber = "+48111222333", firstName = "Fryzjer", lastName = "Testowy"))
        val customer =
            userRepository.save(User(phoneNumber = "+48999888777", firstName = "Klient", lastName = "Testowy"))
        companyEmployeeRepository.save(
            CompanyEmployee(
                companyId = company.id!!,
                userId = employee.id,
                role = "EMPLOYEE"
            )
        )

        val service = serviceRepository.save(
            Offering(
                companyId = company.id!!,
                name = "Farbowanie",
                durationMinutes = 120,
                price = 250 // Usługa długa, 2 godziny!
            )
        )

        employeeId = employee.id
        serviceId = service.id!!

        // Grafik pracownika: 9:00-17:00 dla dnia testu
        workScheduleRepository.save(
            EmployeeWorkSchedule(
                companyId = companyId,
                employeeId = employeeId,
                dayOfWeek = testDate.dayOfWeek,
                startTime = LocalTime.of(9, 0),
                endTime = LocalTime.of(17, 0)
            )
        )

        // Wstawiamy rezerwację od 10:00 do 12:00
        reservationRepository.save(
            Reservation(
                companyId = company.id!!,
                customerId = customer.id,
                employeeId = employee.id,
                serviceId = service.id!!,
                price = 250,
                startTime = testDate.atTime(10, 0),
                endTime = testDate.atTime(12, 0),
                status = ReservationStatus.CONFIRMED
            )
        )
    }

    @Test
    fun `GET api-availability should return calendar gaps without authorization (200)`() {
        // Zauważ, że nie dodajemy nagłówka Authorization!
        mockMvc.get("/api/availability") {
            param("employeeId", employeeId.toString())
            param("serviceId", serviceId.toString())
            param("date", testDate.toString()) // format YYYY-MM-DD
        }.andExpect {
            status { isOk() }
            // Oczekujemy tablicy JSON z obiektami {time, price, originalPrice}
            jsonPath("$") { isArray() }

            // Usługa trwa 2 godziny.
            // Przed rezerwacją z 10:00 zmieści się tylko o 9:00 (zakończy o 11:00 -> NACHODZI!)
            // Więc przed 12:00 nie będzie żadnego wolnego terminu dla usługi 2-godzinnej (bo salon otwiera o 9:00).
            jsonPath("$[?(@.time == '09:00:00')]") { doesNotExist() }
            jsonPath("$[?(@.time == '10:00:00')]") { doesNotExist() }

            // Od 12:00 zaczyna się pasmo wolnego czasu (12:00-14:00 to 2h, OK)
            jsonPath("$[?(@.time == '12:00:00')]") { exists() }
            jsonPath("$[?(@.time == '12:30:00')]") { exists() }
            jsonPath("$[?(@.time == '15:00:00')]") { exists() } // 15:00-17:00, ostatni możliwy!

            // Usługa wzięta o 15:30 skończyłaby się o 17:30 (poza godzinami pracy), więc nie ma jej na liście
            jsonPath("$[?(@.time == '15:30:00')]") { doesNotExist() }
        }
    }

    @Test
    fun `schedule block should exclude overlapping slots from availability`() {
        // Blokujemy pracownika od 13:00 do 14:00 (usługa trwa 2h, więc 12:00-14:00 i 13:00-15:00 są zablokowane)
        scheduleBlockRepository.save(
            ScheduleBlock(
                companyId = companyId,
                employeeId = employeeId,
                startTime = testDate.atTime(13, 0),
                endTime = testDate.atTime(14, 0)
            )
        )

        mockMvc.get("/api/availability") {
            param("employeeId", employeeId.toString())
            param("serviceId", serviceId.toString())
            param("date", testDate.toString())
        }.andExpect {
            status { isOk() }

            // Slot 12:00-14:00 nachodzi na blokadę 13:00-14:00 -> niedostępny
            jsonPath("$[?(@.time == '12:00:00')]") { doesNotExist() }

            // Slot 13:00-15:00 nachodzi na blokadę 13:00-14:00 -> niedostępny
            jsonPath("$[?(@.time == '13:00:00')]") { doesNotExist() }

            // Slot 14:00-16:00 zaczyna się po blokadzie -> dostępny
            jsonPath("$[?(@.time == '14:00:00')]") { exists() }
        }
    }

    @Test
    fun `should return empty list when employee has no schedule for the requested day`() {
        // Usuwamy wpis grafiku dla dnia testu
        workScheduleRepository.deleteAll()

        mockMvc.get("/api/availability") {
            param("employeeId", employeeId.toString())
            param("serviceId", serviceId.toString())
            param("date", testDate.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    @Test
    fun `slots should include price and originalPrice fields`() {
        mockMvc.get("/api/availability") {
            param("employeeId", employeeId.toString())
            param("serviceId", serviceId.toString())
            param("date", testDate.toString())
        }.andExpect {
            status { isOk() }
            // Each slot object must have time, price and originalPrice; no discount configured so both equal catalog price
            jsonPath("$[0].time") { exists() }
            jsonPath("$[0].price") { value(250) }
            jsonPath("$[0].originalPrice") { value(250) }
        }
    }

    @Test
    fun `slots should have discounted price when last-minute discount is configured and testDate is within window`() {
        // Re-create company with 20% discount and a huge window so testDate (2 days ahead) is inside
        companyRepository.deleteAll() // cascade deletes are handled by @BeforeEach ordering; here we rebuild only company
        val discountCompany = companyRepository.save(
            Company(
                name = "Salon Rabatowy",
                lastMinuteDiscountPercent = 20,
                lastMinuteDiscountHours = 9999
            )
        )
        val newCompanyId = discountCompany.id!!

        // Re-create employee and offering under the new company
        val newEmployee =
            userRepository.save(User(phoneNumber = "+48777666555", firstName = "Fryzjer2", lastName = "Testowy2"))
        companyEmployeeRepository.save(
            CompanyEmployee(
                companyId = newCompanyId,
                userId = newEmployee.id,
                role = "EMPLOYEE"
            )
        )
        val newService = serviceRepository.save(
            Offering(companyId = newCompanyId, name = "Koloryzacja", durationMinutes = 60, price = 100)
        )
        workScheduleRepository.save(
            EmployeeWorkSchedule(
                companyId = newCompanyId,
                employeeId = newEmployee.id,
                dayOfWeek = testDate.dayOfWeek,
                startTime = LocalTime.of(9, 0),
                endTime = LocalTime.of(11, 0)
            )
        )

        mockMvc.get("/api/availability") {
            param("employeeId", newEmployee.id.toString())
            param("serviceId", newService.id!!.toString())
            param("date", testDate.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$") { isArray() }
            // price = 100 * (100 - 20) / 100 = 80; originalPrice = 100
            jsonPath("$[0].price") { value(80) }
            jsonPath("$[0].originalPrice") { value(100) }
        }
    }

    @Test
    fun `should return 400 when employee has service assignments but not for requested service`() {
        val otherService = serviceRepository.save(
            Offering(companyId = companyId, name = "Inny Serwis", durationMinutes = 30, price = 50)
        )
        // Pracownik ma przypisanie tylko do innej usługi
        assignmentRepository.save(
            EmployeeOfferingAssignment(
                companyId = companyId,
                employeeId = employeeId,
                offeringId = otherService.id!!
            )
        )

        mockMvc.get("/api/availability") {
            param("employeeId", employeeId.toString())
            param("serviceId", serviceId.toString())
            param("date", testDate.toString())
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `should use employee work schedule hours for slot boundaries`() {
        // Grafik skrócony: 13:00-17:00 tylko dla dnia testu (nadpisujemy setup)
        workScheduleRepository.deleteAll()
        workScheduleRepository.save(
            EmployeeWorkSchedule(
                companyId = companyId,
                employeeId = employeeId,
                dayOfWeek = testDate.dayOfWeek,
                startTime = LocalTime.of(13, 0),
                endTime = LocalTime.of(17, 0)
            )
        )

        mockMvc.get("/api/availability") {
            param("employeeId", employeeId.toString())
            param("serviceId", serviceId.toString())
            param("date", testDate.toString())
        }.andExpect {
            status { isOk() }
            // Firma otwarta od 9:00, ale pracownik od 13:00 – slot 9:00 nie powinien istnieć
            jsonPath("$[?(@.time == '09:00:00')]") { doesNotExist() }
            // Slot 13:00-15:00 jest dostępny
            jsonPath("$[?(@.time == '13:00:00')]") { exists() }
            // Slot 15:00-17:00 jest dostępny
            jsonPath("$[?(@.time == '15:00:00')]") { exists() }
            // Slot 15:30-17:30 przekracza koniec grafiku
            jsonPath("$[?(@.time == '15:30:00')]") { doesNotExist() }
        }
    }
}
