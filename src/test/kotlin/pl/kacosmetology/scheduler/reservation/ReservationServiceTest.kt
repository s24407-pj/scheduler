package pl.kacosmetology.scheduler.reservation

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.context.ApplicationEventPublisher
import pl.kacosmetology.scheduler.availability.EmployeeAvailabilityPolicy
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.user.CompanyCustomerBlock
import pl.kacosmetology.scheduler.user.CompanyCustomerBlockRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import java.time.LocalDateTime
import java.util.*

@ExtendWith(MockKExtension::class)
class ReservationServiceTest {

    @MockK
    private lateinit var reservationRepository: ReservationRepository

    @MockK
    private lateinit var serviceRepository: OfferingRepository

    @MockK
    private lateinit var userRepository: UserRepository

    @MockK
    private lateinit var assignmentRepository: EmployeeOfferingAssignmentRepository

    @MockK
    private lateinit var companyRepository: CompanyRepository

    @MockK(relaxed = true)
    private lateinit var applicationEventPublisher: ApplicationEventPublisher

    @MockK
    private lateinit var companyCustomerBlockRepository: CompanyCustomerBlockRepository

    @MockK(relaxed = true)
    private lateinit var employeeAvailabilityPolicy: EmployeeAvailabilityPolicy

    @InjectMockKs
    private lateinit var reservationService: ReservationService

    private val customerId = 100L
    private val employeeId = 200L
    private val serviceId = 300L
    private val companyId = 1L
    private val startTime = LocalDateTime.of(2024, 5, 20, 10, 0) // Jakaś losowa data

    private val mockCustomer =
        User(id = customerId, phoneNumber = "+48111000111", firstName = "Jan", lastName = "Kowalski")

    @Test
    fun `should create reservation with price snapshot and calculated end time`() {
        // GIVEN
        val duration = 45
        val servicePrice = 150
        val mockService = Offering(
            id = serviceId, companyId = companyId, name = "Strzyżenie",
            durationMinutes = duration, price = servicePrice
        )

        every { userRepository.existsById(customerId) } returns true
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns null
        every { companyRepository.findById(companyId) } returns Optional.of(Company(id = companyId, name = "Salon"))
        // Zakładamy, że termin jest wolny
        every {
            reservationRepository.existsOverlapping(
                employeeId,
                startTime,
                startTime.plusMinutes(duration.toLong())
            )
        } returns false

        // Mockujemy zapis (zwracamy to, co dostaliśmy)
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = reservationService.createReservation(customerId, employeeId, serviceId, startTime)

        // THEN
        assertNotNull(result)
        assertEquals(companyId, result.companyId)
        assertEquals(customerId, result.customerId)
        assertEquals(servicePrice, result.price, "Snapshot ceny powinien zgadzać się z ceną usługi (brak rabatu)!")
        assertEquals(
            startTime.plusMinutes(duration.toLong()),
            result.endTime,
            "Czas zakończenia powinien być poprawnie wyliczony"
        )
        assertEquals(ReservationStatus.PENDING, result.status)

        verify(exactly = 1) { reservationRepository.save(any()) }
    }

    @Test
    fun `should throw when time slot is already taken`() {
        // GIVEN
        val mockService = Offering(
            id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 50
        )

        every { userRepository.existsById(customerId) } returns true
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns null
        every { companyRepository.findById(companyId) } returns Optional.of(Company(id = companyId, name = "Salon"))

        every {
            employeeAvailabilityPolicy.assertAvailable(employeeId, any(), any())
        } throws IllegalStateException("Ten termin jest już zajęty")

        // WHEN & THEN
        val exception = assertThrows<IllegalStateException> {
            reservationService.createReservation(customerId, employeeId, serviceId, startTime)
        }
        assertEquals("Ten termin jest już zajęty", exception.message)

        // Upewniamy się, że repozytorium nigdy nie próbowało zapisać tej rezerwacji!
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    @Test
    fun `should throw when service does not exist`() {
        // GIVEN
        every { userRepository.existsById(customerId) } returns true
        every { serviceRepository.findById(serviceId) } returns Optional.empty()

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            reservationService.createReservation(customerId, employeeId, serviceId, startTime)
        }
        assertEquals("Usługa nie istnieje", exception.message)
    }

    @Test
    fun `should throw when employee has service assignments but not for this service`() {
        // GIVEN
        val mockService = Offering(
            id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 50
        )
        every { userRepository.existsById(customerId) } returns true
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns true
        every { assignmentRepository.existsByEmployeeIdAndOfferingId(employeeId, serviceId) } returns false

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            reservationService.createReservation(customerId, employeeId, serviceId, startTime)
        }
        assertEquals("Ten pracownik nie wykonuje wybranej usługi", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    @Test
    fun `cancelReservation should change status to CANCELLED for reservation owner`() {
        // GIVEN
        val reservationId = 1L
        val reservation = Reservation(
            id = reservationId,
            companyId = companyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.PENDING
        )

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        reservationService.cancelReservation(reservationId, customerId)

        // THEN
        assertEquals(ReservationStatus.CANCELLED, reservation.status)
        verify(exactly = 1) { reservationRepository.save(reservation) }
    }

    @Test
    fun `cancelReservation should throw for NO_SHOW reservation`() {
        // GIVEN
        val reservationId = 1L
        val reservation = Reservation(
            id = reservationId,
            companyId = companyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.NO_SHOW
        )

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)

        // WHEN & THEN
        val exception = assertThrows<IllegalStateException> {
            reservationService.cancelReservation(reservationId, customerId)
        }
        assertEquals("Nie można anulować rezerwacji oznaczonej jako nieobecność", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    @Test
    fun `cancelReservation should throw when user tries to cancel someone else's reservation`() {
        // GIVEN
        val reservationId = 1L
        val hackerId = 999L // Inne ID klienta!
        val reservation = Reservation(
            id = reservationId,
            companyId = companyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.PENDING
        )

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)

        // WHEN & THEN
        val exception = assertThrows<IllegalStateException> {
            reservationService.cancelReservation(reservationId, hackerId)
        }
        assertEquals("Nie możesz anulować nie swojej rezerwacji", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    @Test
    fun `completeReservation should change status to COMPLETED`() {
        // GIVEN
        val reservationId = 1L
        val reservation = Reservation(
            id = reservationId,
            companyId = companyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.PENDING
        )

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        reservationService.completeReservation(reservationId, companyId)

        // THEN
        assertEquals(ReservationStatus.COMPLETED, reservation.status)
        verify(exactly = 1) { reservationRepository.save(reservation) }
    }

    @Test
    fun `completeReservation should throw for cancelled reservation`() {
        // GIVEN
        val reservationId = 1L
        val reservation = Reservation(
            id = reservationId,
            companyId = companyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.CANCELLED
        ) // Już anulowana!

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)

        // WHEN & THEN
        val exception = assertThrows<IllegalStateException> {
            reservationService.completeReservation(reservationId, companyId)
        }
        assertEquals("Nie można zakończyć odwołanej wizyty", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    @Test
    fun `completeReservation should throw for NO_SHOW reservation`() {
        // GIVEN
        val reservationId = 1L
        val reservation = Reservation(
            id = reservationId,
            companyId = companyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.NO_SHOW
        )

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)

        // WHEN & THEN
        val exception = assertThrows<IllegalStateException> {
            reservationService.completeReservation(reservationId, companyId)
        }
        assertEquals("Nie można zakończyć wizyty oznaczonej jako nieobecność", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    @Test
    fun `completeReservation should throw when reservation belongs to different company`() {
        val reservationId = 1L
        val otherCompanyId = 99L
        val reservation = Reservation(
            id = reservationId,
            companyId = otherCompanyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.PENDING
        )

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)

        val exception = assertThrows<IllegalStateException> {
            reservationService.completeReservation(reservationId, companyId)
        }
        assertEquals("Brak dostępu do tej rezerwacji", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    @Test
    fun `markNoShow should throw when reservation belongs to different company`() {
        val reservationId = 1L
        val otherCompanyId = 99L
        val reservation = Reservation(
            id = reservationId,
            companyId = otherCompanyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.PENDING
        )

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)

        val exception = assertThrows<IllegalStateException> {
            reservationService.markNoShow(reservationId, companyId)
        }
        assertEquals("Brak dostępu do tej rezerwacji", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    // ============================================================
    // Staff booking tests
    // ============================================================

    @Test
    fun `createReservationByStaff should create reservation for existing customer`() {
        // GIVEN
        val existingCustomer =
            User(id = customerId, phoneNumber = "+48111111111", firstName = "Ala", lastName = "Nowak")
        val duration = 30
        val mockService =
            Offering(id = serviceId, companyId = companyId, name = "Paznokcie", durationMinutes = duration, price = 80)

        every { userRepository.findByPhoneNumber(existingCustomer.phoneNumber) } returns existingCustomer
        every { userRepository.existsById(customerId) } returns true
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns null
        every { companyRepository.findById(companyId) } returns Optional.of(Company(id = companyId, name = "Salon"))
        every {
            reservationRepository.existsOverlapping(
                employeeId,
                startTime,
                startTime.plusMinutes(duration.toLong())
            )
        } returns false
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = reservationService.createReservationByStaff(
            employeeId = employeeId,
            serviceId = serviceId,
            startTime = startTime,
            customerPhone = existingCustomer.phoneNumber,
            customerFirstName = null,
            customerLastName = null
        )

        // THEN
        assertEquals(customerId, result.customerId)
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `createReservationByStaff should create new customer when phone is unknown`() {
        // GIVEN
        val newPhone = "+48999000111"
        val newCustomer = User(id = 500L, phoneNumber = newPhone, firstName = "Nowy", lastName = "Klient")
        val duration = 45
        val mockService =
            Offering(id = serviceId, companyId = companyId, name = "Masaż", durationMinutes = duration, price = 120)

        every { userRepository.findByPhoneNumber(newPhone) } returns null
        every { userRepository.save(any()) } returns newCustomer
        every { userRepository.existsById(500L) } returns true
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, 500L) } returns null
        every { companyRepository.findById(companyId) } returns Optional.of(Company(id = companyId, name = "Salon"))
        every {
            reservationRepository.existsOverlapping(
                employeeId,
                startTime,
                startTime.plusMinutes(duration.toLong())
            )
        } returns false
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = reservationService.createReservationByStaff(
            employeeId = employeeId,
            serviceId = serviceId,
            startTime = startTime,
            customerPhone = newPhone,
            customerFirstName = "Nowy",
            customerLastName = "Klient"
        )

        // THEN
        assertEquals(newCustomer.id, result.customerId)
        verify(exactly = 1) { userRepository.save(any()) }
    }

    @Test
    fun `createReservationByStaff should throw when new customer has no name provided`() {
        // GIVEN
        val unknownPhone = "+48000111222"
        every { userRepository.findByPhoneNumber(unknownPhone) } returns null

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            reservationService.createReservationByStaff(
                employeeId = employeeId,
                serviceId = serviceId,
                startTime = startTime,
                customerPhone = unknownPhone,
                customerFirstName = null,
                customerLastName = null
            )
        }
        assertEquals("Imię i nazwisko klienta są wymagane przy tworzeniu nowego konta", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    // ============================================================
    // markNoShow tests
    // ============================================================

    @Test
    fun `markNoShow should set status to NO_SHOW and increment customer no-show count`() {
        // GIVEN
        val reservationId = 1L
        val reservation = Reservation(
            id = reservationId,
            companyId = companyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.PENDING
        )
        val block = CompanyCustomerBlock(companyId = companyId, customerId = customerId, noShowCount = 0)
        val company = Company(id = companyId, name = "Salon", maxNoShows = 3)

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationRepository.save(any()) } answers { firstArg() }
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns block
        every { companyRepository.findById(companyId) } returns Optional.of(company)
        every { companyCustomerBlockRepository.save(any()) } answers { firstArg() }

        // WHEN
        reservationService.markNoShow(reservationId, companyId)

        // THEN
        assertEquals(ReservationStatus.NO_SHOW, reservation.status)
        assertEquals(1, block.noShowCount)
        assertTrue(!block.blocked)
        verify(exactly = 1) { reservationRepository.save(reservation) }
        verify(exactly = 1) { companyCustomerBlockRepository.save(block) }
    }

    @Test
    fun `markNoShow should create new block record when none exists`() {
        // GIVEN
        val reservationId = 1L
        val reservation = Reservation(
            id = reservationId,
            companyId = companyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.PENDING
        )
        val company = Company(id = companyId, name = "Salon", maxNoShows = 3)

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationRepository.save(any()) } answers { firstArg() }
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns null
        every { companyRepository.findById(companyId) } returns Optional.of(company)
        every { companyCustomerBlockRepository.save(any()) } answers { firstArg() }

        // WHEN
        reservationService.markNoShow(reservationId, companyId)

        // THEN
        assertEquals(ReservationStatus.NO_SHOW, reservation.status)
        verify(exactly = 1) { companyCustomerBlockRepository.save(any()) }
    }

    @Test
    fun `markNoShow should auto-block customer when threshold is reached`() {
        // GIVEN
        val reservationId = 1L
        val reservation = Reservation(
            id = reservationId,
            companyId = companyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.CONFIRMED
        )
        val block = CompanyCustomerBlock(companyId = companyId, customerId = customerId, noShowCount = 2)
        val company = Company(id = companyId, name = "Salon", maxNoShows = 3)

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationRepository.save(any()) } answers { firstArg() }
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns block
        every { companyRepository.findById(companyId) } returns Optional.of(company)
        every { companyCustomerBlockRepository.save(any()) } answers { firstArg() }

        // WHEN
        reservationService.markNoShow(reservationId, companyId)

        // THEN
        assertEquals(3, block.noShowCount)
        assertTrue(block.blocked)
    }

    @Test
    fun `markNoShow should not auto-block when threshold is zero`() {
        // GIVEN
        val reservationId = 1L
        val reservation = Reservation(
            id = reservationId,
            companyId = companyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.PENDING
        )
        val block = CompanyCustomerBlock(companyId = companyId, customerId = customerId, noShowCount = 99)
        val company = Company(id = companyId, name = "Salon", maxNoShows = 0)

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationRepository.save(any()) } answers { firstArg() }
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns block
        every { companyRepository.findById(companyId) } returns Optional.of(company)
        every { companyCustomerBlockRepository.save(any()) } answers { firstArg() }

        // WHEN
        reservationService.markNoShow(reservationId, companyId)

        // THEN
        assertTrue(!block.blocked, "threshold=0 powinno wyłączyć automatyczne blokowanie")
    }

    @Test
    fun `markNoShow should throw when reservation status is not PENDING or CONFIRMED`() {
        // GIVEN
        val reservationId = 1L
        val reservation = Reservation(
            id = reservationId,
            companyId = companyId,
            customerId = customerId,
            employeeId = employeeId,
            serviceId = serviceId,
            price = 100,
            startTime = startTime,
            endTime = startTime.plusMinutes(30),
            status = ReservationStatus.COMPLETED
        )

        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)

        // WHEN & THEN
        val exception = assertThrows<IllegalStateException> {
            reservationService.markNoShow(reservationId, companyId)
        }
        assertEquals("Tylko aktywna rezerwacja może być oznaczona jako nieobecność", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    @Test
    fun `createReservation should throw when customer is blocked at company`() {
        // GIVEN
        val mockService = Offering(
            id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 50
        )
        val block = CompanyCustomerBlock(companyId = companyId, customerId = customerId, blocked = true)

        every { userRepository.existsById(customerId) } returns true
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns block

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            reservationService.createReservation(customerId, employeeId, serviceId, startTime)
        }
        assertEquals("Klient jest zablokowany w tej firmie i nie może rezerwować online", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    // ============================================================
    // Last-minute discount tests
    // ============================================================

    @Test
    fun `should snapshot discounted price when booking is within discount window`() {
        // GIVEN - 15% discount for slots within 48 hours; slot starts in 2 hours (within window)
        val basePrice = 200
        val nearFutureStart = LocalDateTime.now().plusHours(2)
        val mockService =
            Offering(id = serviceId, companyId = companyId, name = "Masaż", durationMinutes = 60, price = basePrice)
        val discountCompany =
            Company(id = companyId, name = "Salon", lastMinuteDiscountPercent = 15, lastMinuteDiscountHours = 48)

        every { userRepository.existsById(customerId) } returns true
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns null
        every { companyRepository.findById(companyId) } returns Optional.of(discountCompany)
        every {
            reservationRepository.existsOverlapping(
                employeeId,
                nearFutureStart,
                nearFutureStart.plusMinutes(60)
            )
        } returns false
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = reservationService.createReservation(customerId, employeeId, serviceId, nearFutureStart)

        // THEN - 200 * (100 - 15) / 100 = 170
        assertEquals(170, result.price, "Price should be discounted when slot is within the discount window")
    }

    // ============================================================
    // Min booking advance tests
    // ============================================================

    @Test
    fun `createReservation should throw when startTime is within min booking advance window`() {
        // GIVEN - company requires 60 min advance; slot starts only 30 min from now
        val nearFutureStart = LocalDateTime.now().plusMinutes(30)
        val mockService =
            Offering(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 50)
        val advanceCompany = Company(id = companyId, name = "Salon", minBookingAdvanceMinutes = 60)

        every { userRepository.existsById(customerId) } returns true
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns null
        every { companyRepository.findById(companyId) } returns Optional.of(advanceCompany)

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            reservationService.createReservation(customerId, employeeId, serviceId, nearFutureStart)
        }
        assertEquals("Rezerwację można złożyć co najmniej 60 minut przed wizytą", exception.message)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    @Test
    fun `createReservationByStaff should bypass min booking advance check`() {
        // GIVEN - same company with 60 min advance; staff can still book 30 min in future
        val nearFutureStart = LocalDateTime.now().plusMinutes(30)
        val existingCustomer =
            User(id = customerId, phoneNumber = "+48111111111", firstName = "Ala", lastName = "Nowak")
        val duration = 30
        val mockService =
            Offering(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = duration, price = 50)
        val advanceCompany = Company(id = companyId, name = "Salon", minBookingAdvanceMinutes = 60)

        every { userRepository.findByPhoneNumber(existingCustomer.phoneNumber) } returns existingCustomer
        every { userRepository.existsById(customerId) } returns true
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns null
        every { companyRepository.findById(companyId) } returns Optional.of(advanceCompany)
        every {
            reservationRepository.existsOverlapping(
                employeeId,
                nearFutureStart,
                nearFutureStart.plusMinutes(duration.toLong())
            )
        } returns false
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN - staff booking should succeed despite advance requirement
        val result = reservationService.createReservationByStaff(
            employeeId = employeeId,
            serviceId = serviceId,
            startTime = nearFutureStart,
            customerPhone = existingCustomer.phoneNumber,
            customerFirstName = null,
            customerLastName = null
        )

        // THEN
        assertEquals(customerId, result.customerId)
        verify(exactly = 1) { reservationRepository.save(any()) }
    }

    // ============================================================
    // getCompanyReservations tests
    // ============================================================

    @Test
    fun `getCompanyReservations should return reservations filtered by company and employee and date range`() {
        // GIVEN
        val start = LocalDateTime.of(2024, 5, 20, 0, 0)
        val end = LocalDateTime.of(2024, 5, 20, 23, 59)
        val expected = listOf(
            Reservation(
                id = 1L, companyId = companyId, customerId = customerId,
                employeeId = employeeId, serviceId = serviceId, price = 100,
                startTime = startTime, endTime = startTime.plusMinutes(30)
            )
        )
        every {
            reservationRepository.findByCompanyIdAndEmployeeIdAndDateRange(
                companyId,
                employeeId,
                start,
                end
            )
        } returns expected
        every { userRepository.findAllById(listOf(customerId)) } returns listOf(
            pl.kacosmetology.scheduler.user.User(
                id = customerId, phoneNumber = "+48100000001", firstName = "Jan", lastName = "Kowalski"
            )
        )

        // WHEN
        val result = reservationService.getCompanyReservations(companyId, employeeId, start, end)

        // THEN
        assertEquals(1, result.size)
        assertEquals(1L, result[0].id)
        assertEquals("Jan", result[0].customerFirstName)
        assertEquals("Kowalski", result[0].customerLastName)
        verify(exactly = 1) {
            reservationRepository.findByCompanyIdAndEmployeeIdAndDateRange(
                companyId,
                employeeId,
                start,
                end
            )
        }
    }

    @Test
    fun `getCompanyReservations should return empty list when no reservations in range`() {
        // GIVEN
        val start = LocalDateTime.of(2024, 1, 1, 0, 0)
        val end = LocalDateTime.of(2024, 1, 1, 23, 59)
        every {
            reservationRepository.findByCompanyIdAndEmployeeIdAndDateRange(
                companyId,
                employeeId,
                start,
                end
            )
        } returns emptyList()
        every { userRepository.findAllById(emptyList()) } returns emptyList()

        // WHEN
        val result = reservationService.getCompanyReservations(companyId, employeeId, start, end)

        // THEN
        assertTrue(result.isEmpty())
    }

    @Test
    fun `should snapshot full price when discount percent is zero`() {
        // GIVEN - no discount configured
        val basePrice = 150
        val mockService = Offering(
            id = serviceId,
            companyId = companyId,
            name = "Strzyżenie",
            durationMinutes = 30,
            price = basePrice
        )
        val noDiscountCompany = Company(id = companyId, name = "Salon", lastMinuteDiscountPercent = 0)

        every { userRepository.existsById(customerId) } returns true
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, customerId) } returns null
        every { companyRepository.findById(companyId) } returns Optional.of(noDiscountCompany)
        every {
            reservationRepository.existsOverlapping(
                employeeId,
                startTime,
                startTime.plusMinutes(30)
            )
        } returns false
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = reservationService.createReservation(customerId, employeeId, serviceId, startTime)

        // THEN
        assertEquals(basePrice, result.price, "Price should not be discounted when discount is 0")
    }
}
