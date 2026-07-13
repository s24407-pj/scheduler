package pl.kacosmetology.scheduler.reservation

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.notification.NotificationScheduler
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.user.CompanyCustomerBlockRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import software.amazon.awssdk.services.s3.S3Client
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AutoCompleteIntegrationTest {

    @Autowired
    private lateinit var notificationScheduler: NotificationScheduler
    @Autowired
    private lateinit var reservationRepository: ReservationRepository
    @Autowired
    private lateinit var userRepository: UserRepository
    @Autowired
    private lateinit var companyRepository: CompanyRepository
    @Autowired
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository
    @Autowired
    private lateinit var offeringRepository: OfferingRepository
    @Autowired
    private lateinit var companyCustomerBlockRepository: CompanyCustomerBlockRepository

    @MockkBean
    private lateinit var s3Client: S3Client

    private var companyId: Long = 0
    private var employeeId: Long = 0
    private var customerId: Long = 0
    private var offeringId: Long = 0

    @BeforeEach
    fun setup() {
        reservationRepository.deleteAll()
        companyCustomerBlockRepository.deleteAll()
        offeringRepository.deleteAll()
        companyEmployeeRepository.deleteAll()
        userRepository.deleteAll()
        companyRepository.deleteAll()

        val company = companyRepository.save(Company(name = "Test Salon"))
        companyId = company.id!!

        val employee = userRepository.save(User(phoneNumber = "+48100", firstName = "Jan", lastName = "Kowal"))
        employeeId = employee.id!!

        val customer = userRepository.save(User(phoneNumber = "+48200", firstName = "Anna", lastName = "Nowak"))
        customerId = customer.id!!

        val offering = offeringRepository.save(
            Offering(companyId = companyId, name = "Haircut", durationMinutes = 60, price = 100)
        )
        offeringId = offering.id!!
    }

    @Test
    fun `autoCompleteElapsedReservations should complete past PENDING reservations`() {
        val pastStart = LocalDateTime.now().minusHours(3)
        reservationRepository.save(
            Reservation(
                companyId = companyId,
                customerId = customerId,
                employeeId = employeeId,
                serviceId = offeringId,
                price = 100,
                startTime = pastStart,
                endTime = pastStart.plusHours(1),
                status = ReservationStatus.PENDING
            )
        )

        notificationScheduler.autoCompleteElapsedReservations()

        val updated = reservationRepository.findAll().single()
        assertEquals(ReservationStatus.COMPLETED, updated.status)
    }

    @Test
    fun `autoCompleteElapsedReservations should complete past CONFIRMED reservations`() {
        val pastStart = LocalDateTime.now().minusHours(3)
        reservationRepository.save(
            Reservation(
                companyId = companyId,
                customerId = customerId,
                employeeId = employeeId,
                serviceId = offeringId,
                price = 100,
                startTime = pastStart,
                endTime = pastStart.plusHours(1),
                status = ReservationStatus.CONFIRMED
            )
        )

        notificationScheduler.autoCompleteElapsedReservations()

        val updated = reservationRepository.findAll().single()
        assertEquals(ReservationStatus.COMPLETED, updated.status)
    }

    @Test
    fun `autoCompleteElapsedReservations should not touch future reservations`() {
        val futureStart = LocalDateTime.now().plusHours(2)
        reservationRepository.save(
            Reservation(
                companyId = companyId,
                customerId = customerId,
                employeeId = employeeId,
                serviceId = offeringId,
                price = 100,
                startTime = futureStart,
                endTime = futureStart.plusHours(1),
                status = ReservationStatus.PENDING
            )
        )

        notificationScheduler.autoCompleteElapsedReservations()

        val unchanged = reservationRepository.findAll().single()
        assertEquals(ReservationStatus.PENDING, unchanged.status)
    }

    @Test
    fun `autoCompleteElapsedReservations should not touch already cancelled reservations`() {
        val pastStart = LocalDateTime.now().minusHours(3)
        reservationRepository.save(
            Reservation(
                companyId = companyId,
                customerId = customerId,
                employeeId = employeeId,
                serviceId = offeringId,
                price = 100,
                startTime = pastStart,
                endTime = pastStart.plusHours(1),
                status = ReservationStatus.CANCELLED
            )
        )

        notificationScheduler.autoCompleteElapsedReservations()

        val unchanged = reservationRepository.findAll().single()
        assertEquals(ReservationStatus.CANCELLED, unchanged.status)
    }
}
