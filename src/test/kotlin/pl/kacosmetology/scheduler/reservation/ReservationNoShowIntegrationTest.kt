package pl.kacosmetology.scheduler.reservation

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.JwtService
import pl.kacosmetology.scheduler.user.CompanyCustomerBlockRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ReservationNoShowIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var companyRepository: CompanyRepository
    @Autowired private lateinit var companyEmployeeRepository: CompanyEmployeeRepository
    @Autowired private lateinit var serviceRepository: OfferingRepository
    @Autowired private lateinit var reservationRepository: ReservationRepository
    @Autowired private lateinit var companyCustomerBlockRepository: CompanyCustomerBlockRepository

    @MockkBean private lateinit var s3Client: S3Client

    private lateinit var employee: User
    private lateinit var customer: User
    private var companyId: Long = 0
    private lateinit var employeeToken: String
    private lateinit var customerToken: String

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        companyCustomerBlockRepository.deleteAll()
        serviceRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Test Salon", maxNoShows = 3))
        companyId = company.id!!

        employee = userRepository.save(User(phoneNumber = "+48700111001", firstName = "Pracownik", lastName = "Testowy"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = companyId, userId = employee.id, role = "EMPLOYEE"))

        customer = userRepository.save(User(phoneNumber = "+48600222001", firstName = "Klient", lastName = "Testowy"))

        employeeToken = jwtService.generateToken(
            CustomUserDetails(employee, companyId, listOf(SimpleGrantedAuthority("ROLE_EMPLOYEE"))),
            companyId
        )
        customerToken = jwtService.generateToken(
            CustomUserDetails(customer, null, listOf(SimpleGrantedAuthority("ROLE_CUSTOMER"))),
            null
        )
    }

    private fun saveReservation(status: ReservationStatus = ReservationStatus.PENDING): Reservation {
        val service = serviceRepository.save(
            Offering(companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 50)
        )
        return reservationRepository.save(
            Reservation(
                companyId = companyId,
                customerId = customer.id,
                employeeId = employee.id,
                serviceId = service.id!!,
                price = 50,
                startTime = LocalDateTime.now().plusDays(1),
                endTime = LocalDateTime.now().plusDays(1).plusMinutes(30),
                status = status
            )
        )
    }

    @Test
    fun `PATCH no-show as employee should return 204 and mark reservation`() {
        val reservation = saveReservation()

        mockMvc.patch("/api/reservations/${reservation.id}/no-show") {
            header("Authorization", "Bearer $employeeToken")
        }.andExpect {
            status { isNoContent() }
        }

        val updated = reservationRepository.findById(reservation.id!!).get()
        assertEquals(ReservationStatus.NO_SHOW, updated.status)

        val block = companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customer.id)!!
        assertEquals(1, block.noShowCount)
    }

    @Test
    fun `PATCH no-show as customer should return 403`() {
        val reservation = saveReservation()

        mockMvc.patch("/api/reservations/${reservation.id}/no-show") {
            header("Authorization", "Bearer $customerToken")
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `PATCH no-show on already completed reservation should return 409`() {
        val reservation = saveReservation(ReservationStatus.COMPLETED)

        mockMvc.patch("/api/reservations/${reservation.id}/no-show") {
            header("Authorization", "Bearer $employeeToken")
        }.andExpect {
            status { isConflict() }
        }
    }

    @Test
    fun `PATCH no-show on cancelled reservation should return 409`() {
        val reservation = saveReservation(ReservationStatus.CANCELLED)

        mockMvc.patch("/api/reservations/${reservation.id}/no-show") {
            header("Authorization", "Bearer $employeeToken")
        }.andExpect {
            status { isConflict() }
        }
    }

    @Test
    fun `PATCH no-show by staff from different company should return 409`() {
        val reservation = saveReservation()

        // Staff from a different company
        val otherCompany = companyRepository.save(Company(name = "Inny Salon"))
        val otherOwner = userRepository.save(User(phoneNumber = "+48900000099", firstName = "Obcy", lastName = "Owner"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = otherCompany.id!!, userId = otherOwner.id, role = "OWNER"))
        val otherToken = jwtService.generateToken(
            CustomUserDetails(otherOwner, otherCompany.id, listOf(SimpleGrantedAuthority("ROLE_OWNER"))),
            otherCompany.id
        )

        mockMvc.patch("/api/reservations/${reservation.id}/no-show") {
            header("Authorization", "Bearer $otherToken")
        }.andExpect {
            status { isConflict() }
        }

        // Reservation status must be unchanged
        val unchanged = reservationRepository.findById(reservation.id!!).get()
        assertEquals(ReservationStatus.PENDING, unchanged.status)
    }

    @Test
    fun `PATCH complete by staff from different company should return 409`() {
        val reservation = saveReservation()

        val otherCompany = companyRepository.save(Company(name = "Inny Salon B"))
        val otherOwner = userRepository.save(User(phoneNumber = "+48900000098", firstName = "Obcy2", lastName = "Owner2"))
        companyEmployeeRepository.save(CompanyEmployee(companyId = otherCompany.id!!, userId = otherOwner.id, role = "OWNER"))
        val otherToken = jwtService.generateToken(
            CustomUserDetails(otherOwner, otherCompany.id, listOf(SimpleGrantedAuthority("ROLE_OWNER"))),
            otherCompany.id
        )

        mockMvc.patch("/api/reservations/${reservation.id}/complete") {
            header("Authorization", "Bearer $otherToken")
        }.andExpect {
            status { isConflict() }
        }

        val unchanged = reservationRepository.findById(reservation.id!!).get()
        assertEquals(ReservationStatus.PENDING, unchanged.status)
    }

    @Test
    fun `PATCH no-show three times should auto-block the customer`() {
        repeat(3) {
            val r = saveReservation()
            mockMvc.patch("/api/reservations/${r.id}/no-show") {
                header("Authorization", "Bearer $employeeToken")
            }.andExpect { status { isNoContent() } }
        }

        val block = companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customer.id)!!
        assertEquals(3, block.noShowCount)
        assertTrue(block.blocked)
    }
}
