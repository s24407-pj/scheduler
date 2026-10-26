package pl.kacosmetology.scheduler.reservation

import io.mockk.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.util.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForList
import org.springframework.jdbc.core.queryForObject
import org.springframework.security.core.context.SecurityContextHolder
import pl.kacosmetology.scheduler.availability.BookableSlotPolicy
import pl.kacosmetology.scheduler.availability.EmployeeAvailabilityPolicy
import pl.kacosmetology.scheduler.company.*
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.quote.CommercialAgreement
import pl.kacosmetology.scheduler.quote.QuotePolicy
import pl.kacosmetology.scheduler.reservation.dto.EditReservationRequest
import pl.kacosmetology.scheduler.reservation.dto.ReservationActionRequest
import pl.kacosmetology.scheduler.testId
import pl.kacosmetology.scheduler.user.*

/** Unit boundaries complement HTTP/DB tests for lock order, agreement snapshots, edits and revision protection. */
class ReservationServiceTest {
    private val reservations = mockk<ReservationRepository>()
    private val offerings = mockk<OfferingRepository>()
    private val assignments = mockk<EmployeeOfferingAssignmentRepository>()
    private val employees = mockk<CompanyEmployeeRepository>()
    private val companies = mockk<CompanyRepository>()
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)
    private val blocks = mockk<CompanyCustomerBlockRepository>()
    private val availability = mockk<EmployeeAvailabilityPolicy>(relaxed = true)
    private val grid = mockk<BookableSlotPolicy>()
    private val clock = mockk<Clock>()
    private val profiles = mockk<CompanyCustomerRepository>()
    private val quotes = mockk<QuotePolicy>()
    private val audit = mockk<ReservationAuditContext>(relaxed = true)
    private val jdbc = mockk<JdbcTemplate>()
    private val service =
        ReservationService(
            reservations,
            offerings,
            assignments,
            employees,
            companies,
            events,
            blocks,
            availability,
            grid,
            clock,
            profiles,
            quotes,
            audit,
            jdbc,
        )
    private val now = Instant.parse("2030-01-14T10:00:00Z")
    private val start = LocalDateTime.parse("2030-01-14T12:00:00")
    private lateinit var company: Company
    private lateinit var employee: CompanyEmployee
    private lateinit var offering: Offering
    private lateinit var existing: Reservation

    @BeforeEach
    fun setup() {
        SecurityContextHolder.clearContext()
        company = Company(id = testId(1), name = "Salon")
        employee = CompanyEmployee(id = testId(7), companyId = testId(1), userId = testId(20), role = "EMPLOYEE")
        offering = Offering(id = testId(30), companyId = testId(1), name = "Catalog", price = 101, durationMinutes = 30)
        existing =
            Reservation(
                id = testId(50),
                companyId = testId(1),
                customerId = testId(10),
                employeeId = testId(20),
                serviceId = testId(30),
                price = 77,
                startTime = start,
                endTime = start.plusMinutes(30),
                acceptedServiceName = "Accepted",
                agreedDurationMinutes = 30,
            )
        every { clock.instant() } returns now
        every { clock.zone } returns BUSINESS_ZONE
        every { jdbc.queryForList<UUID>(any<String>(), any<UUID>()) } returns listOf(testId(1))
        every { jdbc.queryForList(any<String>(), any<UUID>(), any<String>(), any<String>()) } returns emptyList()
        every { jdbc.queryForObject<Long>(any<String>(), *anyVararg()) } returns 0L
        every { jdbc.queryForObject<Int>(any<String>(), *anyVararg()) } returns 0
        every { jdbc.update(any<String>(), *anyVararg()) } returns 1
        every { companies.findByIdForUpdate(testId(1)) } returns company
        every { companies.findById(testId(1)) } returns Optional.of(company)
        every { employees.findByCompanyIdAndUserIdForUpdate(testId(1), testId(20)) } returns employee
        every { quotes.resolve(any(), any(), any(), any(), any()) } returns CommercialAgreement("Accepted", 77, 30)
        every { grid.isOnGrid(any(), any(), any(), any(), any()) } returns true
        every { blocks.findByCompanyIdAndCustomerId(testId(1), testId(10)) } returns null
        every { reservations.countFutureCustomerBookings(testId(1), testId(10), any()) } returns 0
        every { reservations.existsCustomerOverlapping(any(), any(), any(), any(), any()) } returns false
        every { reservations.findByIdAndCompanyIdForUpdate(testId(50), testId(1)) } answers { existing }
        every { reservations.saveAndFlush(any()) } answers
            {
                val r = firstArg<Reservation>()
                if (r.id != null) r
                else
                    Reservation(
                        id = testId(51),
                        companyId = r.companyId,
                        customerId = r.customerId,
                        employeeId = r.employeeId,
                        serviceId = r.serviceId,
                        price = r.price,
                        startTime = r.startTime,
                        endTime = r.endTime,
                        acceptedServiceName = r.acceptedServiceName,
                        agreedDurationMinutes = r.agreedDurationMinutes,
                        creationSource = r.creationSource,
                        internalNotes = r.internalNotes,
                    )
            }
        every { profiles.findByCompanyIdAndId(testId(1), testId(10)) } returns
            CompanyCustomer(
                id = testId(10),
                companyId = testId(1),
                phoneNumber = "+48500100200",
                firstBookingAt = now,
                firstName = "Local",
                lastName = "Name",
            )
        every { profiles.findByCompanyIdAndIdAndFirstBookingAtIsNotNull(testId(1), testId(10)) } answers
            {
                profiles.findByCompanyIdAndId(testId(1), testId(10))
            }
        every { profiles.save(any()) } answers { firstArg() }
        every { offerings.findByIdAndCompanyId(testId(30), testId(1)) } returns offering
        every { profiles.findByCompanyIdAndPhoneNumber(testId(1), "+48500100200") } returns
            CompanyCustomer(
                id = testId(10),
                companyId = testId(1),
                phoneNumber = "+48500100200",
                firstBookingAt = now,
                firstName = "Local",
                lastName = "Name",
            )
    }

    private fun customer(startAt: LocalDateTime = start) =
        service.createReservation(testId(10), testId(20), testId(30), startAt, requesterCompanyId = testId(1))

    private fun manual(startAt: LocalDateTime = start, owner: Boolean = false, override: Boolean = false) =
        service.createReservationByStaff(
            testId(20),
            testId(30),
            startAt,
            pl.kacosmetology.scheduler.reservation.dto.ExistingCustomerSelection(testId(10)),
            testId(1),
            overrideEmployeeBlock = override,
            owner = owner,
        )

    private fun fails(code: ErrorCode, call: () -> Any?) {
        assertEquals(code, assertThrows<ApiException> { call() }.code)
    }

    @Test
    fun `customer booking snapshots quote terms rather than catalog mutable values`() {
        val result = customer()
        assertTrue(result.created)
        assertEquals(77, result.reservation.price)
        assertEquals("Accepted", result.reservation.acceptedServiceName)
        assertEquals(30, result.reservation.agreedDurationMinutes)
        assertEquals(ReservationStatus.BOOKED, result.reservation.status)
        verify(exactly = 0) { events.publishEvent(any<ReservationChangedEvent>()) }
    }

    @Test
    fun `authoritative time and quote lookup happen after company and employee locks`() {
        customer()
        verifyOrder {
            companies.findByIdForUpdate(testId(1))
            employees.findByCompanyIdAndUserIdForUpdate(testId(1), testId(20))
            clock.instant()
            quotes.resolve(testId(10), testId(30), start, null, now)
        }
    }

    @Test
    fun `missing catalog resource cannot reveal a foreign company`() {
        every { offerings.findByIdAndCompanyId(testId(30), testId(1)) } returns null
        fails(ErrorCode.RESERVATION_NOT_FOUND) { customer() }
    }

    @Test
    fun `unknown global customer cannot create booking`() {
        every { profiles.findByCompanyIdAndId(testId(1), testId(10)) } returns null
        fails(ErrorCode.CUSTOMER_NOT_FOUND) { customer() }
    }

    @Test
    fun `missing company employee and disabled employee fail before persistence`() {
        every { employees.findByCompanyIdAndUserIdForUpdate(testId(1), testId(20)) } returns null
        fails(ErrorCode.RESERVATION_NOT_FOUND) { customer() }
        every { employees.findByCompanyIdAndUserIdForUpdate(testId(1), testId(20)) } returns employee
        employee.enabled = false
        fails(ErrorCode.EMPLOYEE_OFFERING_NOT_ASSIGNED) { customer() }
        verify(exactly = 0) { reservations.saveAndFlush(any()) }
    }

    @Test
    fun `specific mode with empty assignments qualifies for no services`() {
        employee.eligibilityMode = EmployeeEligibilityMode.SPECIFIC_SERVICES
        every { assignments.existsByCompanyIdAndEmployeeIdAndOfferingId(testId(1), testId(20), testId(30)) } returns
            false
        fails(ErrorCode.EMPLOYEE_OFFERING_NOT_ASSIGNED) { customer() }
        verify(exactly = 0) { assignments.existsByEmployeeIdAndOfferingId(any(), any()) }
    }

    @Test
    fun `slot grid remains required for customer but manual booking bypasses it`() {
        every { grid.isOnGrid(any(), any(), any(), any(), any()) } returns false
        fails(ErrorCode.SLOT_UNAVAILABLE) { customer() }
        assertTrue(manual(start.plusMinutes(7)).created)
    }

    @Test
    fun `company customer cap and overlap are hard checks`() {
        every { reservations.countFutureCustomerBookings(testId(1), testId(10), any()) } returns 10
        fails(ErrorCode.BOOKING_LIMIT_REACHED) { customer() }
        every { reservations.countFutureCustomerBookings(testId(1), testId(10), any()) } returns 0
        every { reservations.existsCustomerOverlapping(any(), any(), any(), any(), any()) } returns true
        fails(ErrorCode.SLOT_UNAVAILABLE) { customer() }
    }

    @Test
    fun `employee overlap and block cannot pass a free customer calendar`() {
        every {
            jdbc.queryForObject<Long>(match { it.contains("FROM reservations") }, *anyVararg())
        } returns 1L
        fails(ErrorCode.SLOT_UNAVAILABLE) { customer() }
    }

    @Test
    fun `zero notice requires future and positive notice has inclusive boundary`() {
        every { clock.instant() } returns start.atZone(BUSINESS_ZONE).toInstant()
        fails(ErrorCode.BOOKING_ADVANCE_REQUIRED) { customer() }
        every { clock.instant() } returns now
        company.minBookingAdvanceMinutes = 60
        assertTrue(customer().created)
        company.minBookingAdvanceMinutes = 61
        fails(ErrorCode.BOOKING_ADVANCE_REQUIRED) { customer() }
    }

    @Test
    fun `customer horizon allows entire final date but rejects following date`() {
        val final = LocalDateTime.ofInstant(now, BUSINESS_ZONE).toLocalDate().plusDays(90).atTime(23, 0)
        assertTrue(customer(final).created)
        fails(ErrorCode.BOOKING_WINDOW_EXCEEDED) { customer(final.plusDays(1)) }
    }

    @Test
    fun `gap overlap and transition spanning interval are rejected`() {
        for (local in
            listOf(LocalDateTime.parse("2030-03-31T02:30:00"), LocalDateTime.parse("2030-10-27T02:30:00"))) fails(
            ErrorCode.INVALID_LOCAL_TIME,
        ) {
            customer(local)
        }
        every { quotes.resolve(any(), any(), any(), any(), any()) } returns CommercialAgreement("Accepted", 77, 120)
        fails(ErrorCode.INVALID_LOCAL_TIME) { customer(LocalDateTime.parse("2030-03-31T01:30:00")) }
    }

    @Test
    fun `first salon booking needs both names and does not create profile on failure`() {
        every { profiles.findByCompanyIdAndId(testId(1), testId(10)) } returns
            CompanyCustomer(id = testId(10), companyId = testId(1), phoneNumber = "+48500100200")
        fails(ErrorCode.CUSTOMER_NAME_REQUIRED) { customer() }
        verify(exactly = 0) { profiles.save(any()) }
        service.createReservation(
            testId(10),
            testId(20),
            testId(30),
            start,
            firstVisitDetails = pl.kacosmetology.scheduler.reservation.dto.FirstVisitDetails(" Jan ", " Klient "),
            requesterCompanyId = testId(1),
        )
        verify { profiles.save(match { it.firstName == "Jan" && it.lastName == "Klient" }) }
    }

    @Test
    fun `staff defaults regular price even when a last minute quote would be cheaper`() {
        company.lastMinuteDiscountPercent = 50
        val result = manual()
        assertEquals(101, result.reservation.price)
        assertEquals("Catalog", result.reservation.acceptedServiceName)
        verify(exactly = 0) { quotes.resolve(any(), any(), any(), any(), any()) }
        verify { events.publishEvent(match<ReservationChangedEvent> { !it.notifyCustomer && it.kind == "CREATE" }) }
    }

    @Test
    fun `manual ongoing visit allowed while end at now is rejected`() {
        assertTrue(manual(LocalDateTime.ofInstant(now, BUSINESS_ZONE).minusMinutes(5)).created)
        fails(ErrorCode.INVALID_STATUS_TRANSITION) {
            manual(LocalDateTime.ofInstant(now, BUSINESS_ZONE).minusMinutes(30))
        }
    }

    @Test
    fun `staff cannot create inactive or foreign service bookings`() {
        offering.active = false
        fails(ErrorCode.OFFERING_UNAVAILABLE) { manual() }
        every { offerings.findByIdAndCompanyId(testId(30), testId(1)) } returns null
        fails(ErrorCode.RESERVATION_NOT_FOUND) { manual() }
    }

    @Test
    fun `only owner can bypass block and physical booking conflict still rejects owner`() {
        fails(ErrorCode.ACCESS_DENIED) { manual(override = true) }
        every {
            jdbc.queryForObject<Long>(match { it.contains("FROM reservations") }, *anyVararg())
        } returns 1L
        fails(ErrorCode.SLOT_UNAVAILABLE) { manual(owner = true, override = true) }
    }

    @Test
    fun `unchanged service edit preserves accepted name price duration when catalog inactive`() {
        offering.active = false
        offering.price = 999
        offering.durationMinutes = 90
        val result =
            service.editReservation(
                testId(50),
                testId(1),
                0,
                EditReservationRequest(startTime = start.plusHours(1)),
                true,
            )
        assertEquals(77, result.price)
        assertEquals(30, result.agreedDurationMinutes)
        assertEquals("Accepted", result.acceptedServiceName)
        assertEquals(testId(50), result.id)
    }

    @Test
    fun `explicit edit overrides commercial terms but stale revisions fail`() {
        fails(ErrorCode.CONCURRENT_MODIFICATION) {
            service.editReservation(testId(50), testId(1), 5, EditReservationRequest(price = 88), true)
        }
        val result =
            service.editReservation(
                testId(50),
                testId(1),
                0,
                EditReservationRequest(price = 88, durationMinutes = 45),
                true,
            )
        assertEquals(88, result.price)
        assertEquals(45, result.agreedDurationMinutes)
    }

    @Test
    fun `no op edit creates no new audit event or notification`() {
        service.editReservation(testId(50), testId(1), 0, EditReservationRequest(notifyCustomer = true), true)
        verify(exactly = 0) {
            audit.capture()
            reservations.saveAndFlush(any())
            events.publishEvent(any<ReservationChangedEvent>())
        }
    }

    @Test
    fun `current scheduled end and BOOKED status control edit eligibility`() {
        existing.status = ReservationStatus.COMPLETED
        fails(ErrorCode.INVALID_STATUS_TRANSITION) {
            service.editReservation(testId(50), testId(1), 0, EditReservationRequest(price = 88), true)
        }
        existing.status = ReservationStatus.BOOKED
        every { clock.instant() } returns existing.endTime.atZone(BUSINESS_ZONE).toInstant()
        fails(ErrorCode.INVALID_STATUS_TRANSITION) {
            service.editReservation(
                testId(50),
                testId(1),
                0,
                EditReservationRequest(startTime = start.plusDays(1)),
                true,
            )
        }
    }

    @Test
    fun `staff cancellation requires private reason but elapsed completed records remain cancellable`() {
        fails(ErrorCode.VALIDATION_FAILED) { service.cancelReservationByStaff(testId(50), testId(1), 0) }
        existing.status = ReservationStatus.COMPLETED
        every { clock.instant() } returns start.plusYears(2).atZone(BUSINESS_ZONE).toInstant()
        service.cancelReservationByStaff(testId(50), testId(1), 0, ReservationActionRequest(reason = "Private"), true)
        assertEquals(ReservationStatus.CANCELLED, existing.status)
        assertEquals("Private", existing.internalCancellationReason)
    }

    @Test
    fun `employee no show deadline is inclusive owner has unlimited correction`() {
        every { clock.instant() } returns existing.endTime.atZone(BUSINESS_ZONE).toInstant().plusSeconds(86400)
        service.markNoShow(testId(50), testId(1), 0)
        assertEquals(ReservationStatus.NO_SHOW, existing.status)
        existing.status = ReservationStatus.BOOKED
        every { clock.instant() } returns existing.endTime.atZone(BUSINESS_ZONE).toInstant().plusSeconds(86401)
        fails(ErrorCode.INVALID_STATUS_TRANSITION) { service.markNoShow(testId(50), testId(1), 0) }
        service.markNoShow(testId(50), testId(1), 0, owner = true)
        assertEquals(ReservationStatus.NO_SHOW, existing.status)
    }

    @Test
    fun `foreign lifecycle target is opaque and archive requires cancelled status`() {
        every { reservations.findByIdAndCompanyIdForUpdate(testId(50), testId(1)) } returns null
        fails(ErrorCode.RESERVATION_NOT_FOUND) { service.completeReservation(testId(50), testId(1), 0) }
        every { reservations.findByIdAndCompanyIdForUpdate(testId(50), testId(1)) } returns existing
        fails(ErrorCode.INVALID_STATUS_TRANSITION) { service.archiveReservation(testId(50), testId(1), 0, true) }
        existing.status = ReservationStatus.CANCELLED
        service.archiveReservation(testId(50), testId(1), 0, true)
        assertTrue(existing.archived)
    }

    @Test
    fun `internal notes can be set on staff creation and edited without changing time or status even after completion`() {
        val created =
            service.createReservationByStaff(
                testId(20),
                testId(30),
                start,
                pl.kacosmetology.scheduler.reservation.dto.ExistingCustomerSelection(testId(10)),
                testId(1),
                internalNotes = "  Klientka zgłasza podrażnienie po poprzednim zabiegu.  ",
            )
        assertEquals(
            "Klientka zgłasza podrażnienie po poprzednim zabiegu.",
            created.reservation.internalNotes,
        )

        clearMocks(audit, reservations, events, answers = false)
        existing.status = ReservationStatus.COMPLETED
        every { clock.instant() } returns existing.endTime.plusDays(2).atZone(BUSINESS_ZONE).toInstant()

        val updated =
            service.editReservation(
                testId(50),
                testId(1),
                0,
                EditReservationRequest(
                    internalNotes =
                        "Klientka zgłasza podrażnienie po poprzednim zabiegu. Podczas kolejnej wizyty sprawdzić reakcję skóry",
                    notifyCustomer = true,
                ),
                false,
            )
        assertEquals(ReservationStatus.COMPLETED, updated.status)
        assertEquals(start, updated.startTime)
        assertEquals(
            "Klientka zgłasza podrażnienie po poprzednim zabiegu. Podczas kolejnej wizyty sprawdzić reakcję skóry",
            updated.internalNotes,
        )
        verify(exactly = 1) {
            audit.capture()
            reservations.saveAndFlush(existing)
        }
        verify(exactly = 0) { events.publishEvent(any<ReservationChangedEvent>()) }

        val cleared =
            service.editReservation(
                testId(50),
                testId(1),
                0,
                EditReservationRequest(internalNotes = "   "),
                false,
            )
        assertNull(cleared.internalNotes)

        existing.internalNotes = "Ponowna notatka"
        val clearedByNull =
            service.editReservation(
                testId(50),
                testId(1),
                0,
                EditReservationRequest(internalNotes = null),
                false,
            )
        assertNull(clearedByNull.internalNotes)
    }
}
