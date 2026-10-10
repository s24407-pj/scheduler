package pl.kacosmetology.scheduler.reservation

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.context.ApplicationEventPublisher
import pl.kacosmetology.scheduler.availability.EmployeeAvailabilityPolicy
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.user.CompanyCustomerBlock
import pl.kacosmetology.scheduler.user.CompanyCustomerBlockRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import java.time.Clock
import java.time.Instant
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
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @MockK
    private lateinit var companyRepository: CompanyRepository

    @MockK(relaxed = true)
    private lateinit var applicationEventPublisher: ApplicationEventPublisher

    @MockK
    private lateinit var companyCustomerBlockRepository: CompanyCustomerBlockRepository

    @MockK(relaxed = true)
    private lateinit var employeeAvailabilityPolicy: EmployeeAvailabilityPolicy

    @MockK
    private lateinit var clock: Clock

    @InjectMockKs
    private lateinit var reservationService: ReservationService

    private val customerId = 100L
    private val employeeId = 200L
    private val serviceId = 300L
    private val companyId = 1L
    private val startTime = LocalDateTime.of(2024, 5, 20, 10, 0) // Jakaś losowa data

    private val mockCustomer =
        User(id = customerId, phoneNumber = "+48111000111", firstName = "Jan", lastName = "Kowalski")

    @BeforeEach
    fun setupCompanyEmployeeMembership() {
        every {
            companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(any(), any())
        } returns CompanyEmployee(companyId = companyId, userId = employeeId, role = "EMPLOYEE")
        every { reservationRepository.findActiveBooking(any(), any(), any(), any()) } returns null
        every { clock.instant() } answers { Instant.now() }
        every { clock.zone } returns BUSINESS_ZONE
    }

    private fun stubBookableOffering(durationMinutes: Int = 30, price: Int = 50) {
        every { userRepository.existsById(customerId) } returns true
        every { serviceRepository.findById(serviceId) } returns Optional.of(
            Offering(
                id = serviceId, companyId = companyId, name = "Strzyżenie",
                durationMinutes = durationMinutes, price = price
            )
        )
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, any()) } returns null
        every { companyRepository.findById(companyId) } returns Optional.of(Company(id = companyId, name = "Salon"))
    }

    @Test
    fun `createReservation should return existing active booking instead of creating a duplicate`() {
        stubBookableOffering()
        val existing = Reservation(
            id = 42L, companyId = companyId, customerId = customerId, employeeId = employeeId,
            serviceId = serviceId, price = 50, startTime = startTime, endTime = startTime.plusMinutes(30)
        )
        every {
            reservationRepository.findActiveBooking(customerId, employeeId, serviceId, startTime)
        } returns existing

        val result = reservationService.createReservation(customerId, employeeId, serviceId, startTime)

        assertSame(existing, result.reservation)
        assertFalse(result.created)
        verify(exactly = 0) { reservationRepository.save(any()) }
        verify(exactly = 0) { employeeAvailabilityPolicy.assertAvailable(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { applicationEventPublisher.publishEvent(any<Any>()) }
    }

    @Test
    fun `createReservation should report a newly created booking as created`() {
        stubBookableOffering()
        every { reservationRepository.save(any()) } answers { firstArg() }

        val result = reservationService.createReservation(customerId, employeeId, serviceId, startTime)

        assertTrue(result.created)
        verifyOrder {
            companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, employeeId)
            reservationRepository.findActiveBooking(customerId, employeeId, serviceId, startTime)
            reservationRepository.save(any())
        }
    }

    @Test
    fun `createReservation should reject a local time skipped by the daylight saving change`() {
        val transition = generateSequence(BUSINESS_ZONE.rules.nextTransition(Instant.now())) {
            BUSINESS_ZONE.rules.nextTransition(it.instant)
        }.first { it.isGap }
        val skippedTime = transition.dateTimeBefore.plusMinutes(30)

        val exception = assertThrows<ApiException> {
            reservationService.createReservation(customerId, employeeId, serviceId, skippedTime)
        }

        assertEquals(ErrorCode.INVALID_LOCAL_TIME, exception.code)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    @Test
    fun `createReservation should measure min booking advance against the injected clock`() {
        stubBookableOffering()
        every { companyRepository.findById(companyId) } returns Optional.of(
            Company(id = companyId, name = "Salon", minBookingAdvanceMinutes = 60)
        )
        every { clock.instant() } returns startTime.minusMinutes(30).atZone(BUSINESS_ZONE).toInstant()

        val exception = assertThrows<ApiException> {
            reservationService.createReservation(customerId, employeeId, serviceId, startTime)
        }

        assertEquals(ErrorCode.BOOKING_ADVANCE_REQUIRED, exception.code)
    }

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

        // Mockujemy zapis (zwracamy to, co dostaliśmy)
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = reservationService.createReservation(customerId, employeeId, serviceId, startTime)

        // THEN
        assertNotNull(result)
        assertEquals(companyId, result.reservation.companyId)
        assertEquals(customerId, result.reservation.customerId)
        assertEquals(servicePrice, result.reservation.price, "Snapshot ceny powinien zgadzać się z ceną usługi (brak rabatu)!")
        assertEquals(
            startTime.plusMinutes(duration.toLong()),
            result.reservation.endTime,
            "Czas zakończenia powinien być poprawnie wyliczony"
        )
        assertEquals(ReservationStatus.PENDING, result.reservation.status)

        verify(exactly = 1) { reservationRepository.save(any()) }
        verifyOrder {
            companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, employeeId)
            employeeAvailabilityPolicy.assertAvailable(companyId, employeeId, startTime, startTime.plusMinutes(45))
            reservationRepository.save(any())
        }
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
            employeeAvailabilityPolicy.assertAvailable(companyId, employeeId, any(), any())
        } throws ApiException(ErrorCode.SLOT_UNAVAILABLE, "Ten termin jest już zajęty")

        // WHEN & THEN
        val exception = assertThrows<ApiException> {
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
        val exception = assertThrows<ApiException> {
            reservationService.createReservation(customerId, employeeId, serviceId, startTime)
        }
        assertEquals("Usługa nie istnieje", exception.message)
        assertEquals(ErrorCode.OFFERING_UNAVAILABLE, exception.code)
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
        val exception = assertThrows<ApiException> {
            reservationService.createReservation(customerId, employeeId, serviceId, startTime)
        }
        assertEquals("Ten pracownik nie wykonuje wybranej usługi", exception.message)
        assertEquals(ErrorCode.EMPLOYEE_OFFERING_NOT_ASSIGNED, exception.code)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    @Test
    fun `should throw when employee does not belong to offering company`() {
        // GIVEN
        val mockService = Offering(
            id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 50
        )
        every { userRepository.existsById(customerId) } returns true
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, employeeId) } returns null

        // WHEN & THEN
        val exception = assertThrows<ApiException> {
            reservationService.createReservation(customerId, employeeId, serviceId, startTime)
        }
        assertEquals("Pracownik nie należy do firmy wybranej usługi", exception.message)
        assertEquals(ErrorCode.EMPLOYEE_NOT_IN_COMPANY, exception.code)
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
        val exception = assertThrows<ApiException> {
            reservationService.cancelReservation(reservationId, customerId)
        }
        assertEquals("Nie można anulować rezerwacji oznaczonej jako nieobecność", exception.message)
        assertEquals(ErrorCode.INVALID_STATUS_TRANSITION, exception.code)
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
        val exception = assertThrows<ApiException> {
            reservationService.cancelReservation(reservationId, hackerId)
        }
        assertEquals("Rezerwacja nie istnieje", exception.message)
        assertEquals(ErrorCode.RESERVATION_NOT_FOUND, exception.code)
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
        val exception = assertThrows<ApiException> {
            reservationService.completeReservation(reservationId, companyId)
        }
        assertEquals("Nie można zakończyć odwołanej wizyty", exception.message)
        assertEquals(ErrorCode.INVALID_STATUS_TRANSITION, exception.code)
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
        val exception = assertThrows<ApiException> {
            reservationService.completeReservation(reservationId, companyId)
        }
        assertEquals("Nie można zakończyć wizyty oznaczonej jako nieobecność", exception.message)
        assertEquals(ErrorCode.INVALID_STATUS_TRANSITION, exception.code)
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

        val exception = assertThrows<ApiException> {
            reservationService.completeReservation(reservationId, companyId)
        }
        assertEquals("Rezerwacja nie istnieje", exception.message)
        assertEquals(ErrorCode.RESERVATION_NOT_FOUND, exception.code)
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

        val exception = assertThrows<ApiException> {
            reservationService.markNoShow(reservationId, companyId)
        }
        assertEquals("Rezerwacja nie istnieje", exception.message)
        assertEquals(ErrorCode.RESERVATION_NOT_FOUND, exception.code)
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
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = reservationService.createReservationByStaff(
            employeeId = employeeId,
            serviceId = serviceId,
            startTime = startTime,
            customerPhone = existingCustomer.phoneNumber,
            customerFirstName = null,
            customerLastName = null,
            requesterCompanyId = companyId
        )

        // THEN
        assertEquals(customerId, result.reservation.customerId)
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

        every { userRepository.findByPhoneNumber(newPhone) } returnsMany listOf(null, newCustomer)
        every { userRepository.insertIfPhoneAbsent(newPhone, "Nowy", "Klient") } returns 1
        every { userRepository.existsById(500L) } returns true
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { companyCustomerBlockRepository.findByCompanyIdAndCustomerId(companyId, 500L) } returns null
        every { companyRepository.findById(companyId) } returns Optional.of(Company(id = companyId, name = "Salon"))
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = reservationService.createReservationByStaff(
            employeeId = employeeId,
            serviceId = serviceId,
            startTime = startTime,
            customerPhone = newPhone,
            customerFirstName = "Nowy",
            customerLastName = "Klient",
            requesterCompanyId = companyId
        )

        // THEN
        assertEquals(newCustomer.id!!, result.reservation.customerId)
        verify(exactly = 1) { userRepository.insertIfPhoneAbsent(newPhone, "Nowy", "Klient") }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `createReservationByStaff should use the account created concurrently for the same phone`() {
        val phone = "+48999000222"
        val concurrentlyCreated = User(id = 501L, phoneNumber = phone, firstName = "Inny", lastName = "Klient")
        stubBookableOffering()
        every { userRepository.findByPhoneNumber(phone) } returnsMany listOf(null, concurrentlyCreated)
        every { userRepository.insertIfPhoneAbsent(phone, "Nowy", "Klient") } returns 0
        every { userRepository.existsById(501L) } returns true
        every { reservationRepository.save(any()) } answers { firstArg() }

        val result = reservationService.createReservationByStaff(
            employeeId = employeeId,
            serviceId = serviceId,
            startTime = startTime,
            customerPhone = phone,
            customerFirstName = "Nowy",
            customerLastName = "Klient",
            requesterCompanyId = companyId
        )

        assertEquals(501L, result.reservation.customerId)
    }

    @Test
    fun `createReservationByStaff should throw when new customer has no name provided`() {
        // GIVEN
        val unknownPhone = "+48000111222"
        val mockService =
            Offering(id = serviceId, companyId = companyId, name = "Masaż", durationMinutes = 45, price = 120)
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)
        every { userRepository.findByPhoneNumber(unknownPhone) } returns null

        // WHEN & THEN
        val exception = assertThrows<ApiException> {
            reservationService.createReservationByStaff(
                employeeId = employeeId,
                serviceId = serviceId,
                startTime = startTime,
                customerPhone = unknownPhone,
                customerFirstName = null,
                customerLastName = null,
                requesterCompanyId = companyId
            )
        }
        assertEquals("Imię i nazwisko klienta są wymagane przy tworzeniu nowego konta", exception.message)
        assertEquals(ErrorCode.CUSTOMER_NAME_REQUIRED, exception.code)
        verify(exactly = 0) { reservationRepository.save(any()) }
    }

    @Test
    fun `createReservationByStaff should throw before creating customer when service belongs to another company`() {
        // GIVEN
        val requesterCompanyId = 10L
        val offeringCompanyId = 20L
        val unknownPhone = "+48000999888"
        val mockService =
            Offering(id = serviceId, companyId = offeringCompanyId, name = "Masaż", durationMinutes = 45, price = 120)
        every { serviceRepository.findById(serviceId) } returns Optional.of(mockService)

        // WHEN & THEN
        val exception = assertThrows<ApiException> {
            reservationService.createReservationByStaff(
                employeeId = employeeId,
                serviceId = serviceId,
                startTime = startTime,
                customerPhone = unknownPhone,
                customerFirstName = "Nowy",
                customerLastName = "Klient",
                requesterCompanyId = requesterCompanyId
            )
        }
        assertEquals("Usługa nie należy do firmy pracownika", exception.message)
        assertEquals(ErrorCode.OFFERING_UNAVAILABLE, exception.code)
        verify(exactly = 0) { userRepository.save(any()) }
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
        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationRepository.save(any()) } answers { firstArg() }
        every { companyCustomerBlockRepository.incrementNoShowCountAndApplyBlock(companyId, customerId) } returns 1

        // WHEN
        reservationService.markNoShow(reservationId, companyId)

        // THEN
        assertEquals(ReservationStatus.NO_SHOW, reservation.status)
        verify(exactly = 1) { reservationRepository.save(reservation) }
        verify(exactly = 1) {
            companyCustomerBlockRepository.incrementNoShowCountAndApplyBlock(companyId, customerId)
        }
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
        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationRepository.save(any()) } answers { firstArg() }
        every { companyCustomerBlockRepository.incrementNoShowCountAndApplyBlock(companyId, customerId) } returns 1

        // WHEN
        reservationService.markNoShow(reservationId, companyId)

        // THEN
        assertEquals(ReservationStatus.NO_SHOW, reservation.status)
        verify(exactly = 1) {
            companyCustomerBlockRepository.incrementNoShowCountAndApplyBlock(companyId, customerId)
        }
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
        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationRepository.save(any()) } answers { firstArg() }
        every { companyCustomerBlockRepository.incrementNoShowCountAndApplyBlock(companyId, customerId) } returns 3

        // WHEN
        reservationService.markNoShow(reservationId, companyId)

        // THEN
        verify(exactly = 1) {
            companyCustomerBlockRepository.incrementNoShowCountAndApplyBlock(companyId, customerId)
        }
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
        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationRepository.save(any()) } answers { firstArg() }
        every { companyCustomerBlockRepository.incrementNoShowCountAndApplyBlock(companyId, customerId) } returns 100

        // WHEN
        reservationService.markNoShow(reservationId, companyId)

        // THEN
        verify(exactly = 1) {
            companyCustomerBlockRepository.incrementNoShowCountAndApplyBlock(companyId, customerId)
        }
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
        val exception = assertThrows<ApiException> {
            reservationService.markNoShow(reservationId, companyId)
        }
        assertEquals("Tylko aktywna rezerwacja może być oznaczona jako nieobecność", exception.message)
        assertEquals(ErrorCode.INVALID_STATUS_TRANSITION, exception.code)
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
        val exception = assertThrows<ApiException> {
            reservationService.createReservation(customerId, employeeId, serviceId, startTime)
        }
        assertEquals("Klient jest zablokowany w tej firmie i nie może rezerwować online", exception.message)
        assertEquals(ErrorCode.CUSTOMER_BLOCKED, exception.code)
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
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = reservationService.createReservation(customerId, employeeId, serviceId, nearFutureStart)

        // THEN - 200 * (100 - 15) / 100 = 170
        assertEquals(170, result.reservation.price, "Price should be discounted when slot is within the discount window")
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
        val exception = assertThrows<ApiException> {
            reservationService.createReservation(customerId, employeeId, serviceId, nearFutureStart)
        }
        assertEquals("Rezerwację można złożyć co najmniej 60 minut przed wizytą", exception.message)
        assertEquals(ErrorCode.BOOKING_ADVANCE_REQUIRED, exception.code)
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
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN - staff booking should succeed despite advance requirement
        val result = reservationService.createReservationByStaff(
            employeeId = employeeId,
            serviceId = serviceId,
            startTime = nearFutureStart,
            customerPhone = existingCustomer.phoneNumber,
            customerFirstName = null,
            customerLastName = null,
            requesterCompanyId = companyId
        )

        // THEN
        assertEquals(customerId, result.reservation.customerId)
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
            User(
                id = customerId, phoneNumber = "+48100000001", firstName = "Jan", lastName = "Kowalski"
            )
        )

        // WHEN
        val result = reservationService.getCompanyReservations(companyId, employeeId, start, end)

        // THEN
        assertEquals(1, result.size)
        assertEquals(1L, result[0].id!!)
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
        every { reservationRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = reservationService.createReservation(customerId, employeeId, serviceId, startTime)

        // THEN
        assertEquals(basePrice, result.reservation.price, "Price should not be discounted when discount is 0")
    }
}
