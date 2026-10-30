package pl.kacosmetology.scheduler.availability

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.*
import java.util.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.ResourceLock
import pl.kacosmetology.scheduler.company.*
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.CustomerOccupiedInterval
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.testId
import pl.kacosmetology.scheduler.workschedule.EmployeeScheduleOverrideRepository
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkSchedule
import pl.kacosmetology.scheduler.workschedule.EmployeeWorkScheduleRepository

@ResourceLock("JVM_TIMEZONE")
class AvailabilityContractTest {
    private val originalZone = TimeZone.getDefault()
    private val clock = Clock.fixed(Instant.parse("2030-01-01T08:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.of(2030, 1, 1)
    private val offerings = mockk<OfferingRepository>()
    private val companies = mockk<CompanyRepository>()
    private val employees = mockk<CompanyEmployeeRepository>()
    private val assignments = mockk<EmployeeOfferingAssignmentRepository>()
    private val weekly = mockk<EmployeeWorkScheduleRepository>()
    private val overrides = mockk<EmployeeScheduleOverrideRepository>()
    private val reservations = mockk<ReservationRepository>()
    private val conflicts = mockk<EmployeeAvailabilityPolicy>()
    private val company = Company(id = testId(1), name = "Warszawa")
    private val employee = CompanyEmployee(companyId = testId(1), userId = testId(7), role = "EMPLOYEE")
    private val policy = BookableSlotPolicy(weekly, overrides)
    private val service =
        AvailabilityService(offerings, companies, assignments, employees, conflicts, policy, clock, reservations)

    @BeforeEach
    fun setup() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        every { offerings.findById(testId(3)) } returns
            Optional.of(
                Offering(id = testId(3), companyId = testId(1), name = "Usługa", durationMinutes = 30, price = 100),
            )
        every { companies.findById(testId(1)) } returns Optional.of(company)
        every { employees.findByCompanyIdAndUserId(testId(1), testId(7)) } returns employee
        every { assignments.existsByCompanyIdAndEmployeeIdAndOfferingId(testId(1), testId(7), testId(3)) } returns false
        every { overrides.findByCompanyIdAndEmployeeIdAndScheduleDate(testId(1), testId(7), any()) } returns null
        every { weekly.findByCompanyIdAndEmployeeIdAndDayOfWeek(testId(1), testId(7), any()) } answers
            {
                EmployeeWorkSchedule(
                    companyId = testId(1),
                    employeeId = testId(7),
                    dayOfWeek = thirdArg(),
                    startTime = LocalTime.of(9, 0),
                    endTime = LocalTime.of(11, 0),
                )
            }
        every { conflicts.findConflicts(any(), any(), any(), any()) } returns emptyList()
        every { conflicts.overlapsAny(any(), any(), any()) } answers
            {
                val start = firstArg<LocalDateTime>()
                val end = secondArg<LocalDateTime>()
                thirdArg<List<EmployeeAvailabilityConflict>>().any {
                    start.isBefore(it.endTime) && end.isAfter(it.startTime)
                }
            }
        every { reservations.findCustomerOverlapping(any(), any(), any(), any()) } returns emptyList()
    }

    @AfterEach
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `zero notice is strictly future in Warsaw even with UTC JVM and UTC clock`() {
        val slots = service.getAvailableSlots(testId(7), testId(3), date)
        assertEquals(LocalTime.of(9, 30), slots.first().time)
        assertEquals(3, slots.size)
    }

    @Test
    fun `positive notice exact elapsed boundary is inclusive`() {
        company.minBookingAdvanceMinutes = 30
        assertEquals(LocalTime.of(9, 30), service.getAvailableSlots(testId(7), testId(3), date).first().time)
    }

    @Test
    fun `entire horizon final Warsaw calendar date is bookable`() {
        every { weekly.findByCompanyIdAndEmployeeIdAndDayOfWeek(testId(1), testId(7), any()) } answers
            {
                EmployeeWorkSchedule(
                    companyId = testId(1),
                    employeeId = testId(7),
                    dayOfWeek = thirdArg(),
                    startTime = LocalTime.of(22, 0),
                    endTime = LocalTime.of(23, 59),
                )
            }
        assertEquals(
            LocalTime.of(23, 0),
            service.getAvailableSlots(testId(7), testId(3), date.plusDays(90)).last().time,
        )
        assertTrue(service.getAvailableSlots(testId(7), testId(3), date.plusDays(91)).isEmpty())
    }

    @Test
    fun `specific empty qualification permits no service while disabled account is opaque`() {
        employee.eligibilityMode = EmployeeEligibilityMode.SPECIFIC_SERVICES
        assertEquals(
            ErrorCode.EMPLOYEE_OFFERING_NOT_ASSIGNED,
            assertThrows<ApiException> { service.getAvailableSlots(testId(7), testId(3), date) }.code,
        )
        employee.enabled = false
        assertThrows<NoSuchElementException> { service.getAvailableSlots(testId(7), testId(3), date) }
    }

    @Test
    fun `personal conflicts mark otherwise free slots without hiding them from salon availability`() {
        every { reservations.findCustomerOverlapping(testId(1), testId(12), any(), any()) } returns
            listOf(
                CustomerOccupiedInterval(date.atTime(10, 0), date.atTime(10, 30)),
            )
        val personal = service.getAvailableSlots(testId(7), testId(3), date, testId(12))
        val public = service.getAvailableSlots(testId(7), testId(3), date)
        assertEquals(public, personal.map { it.copy(personalConflict = false) })
        assertEquals(listOf(false, true, false), personal.map { it.personalConflict })
        assertFalse(public.any { it.personalConflict })
        verify(exactly = 1) {
            reservations.findCustomerOverlapping(testId(1), testId(12), date.atTime(9, 0), date.atTime(11, 0))
        }
        verify(exactly = 0) { reservations.existsCustomerOverlapping(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { reservations.findCustomerOverlapping(testId(2), any(), any(), any()) }
    }

    @Test
    fun `personal intervals detect partial overlap and containment`() {
        for ((start, end) in
            listOf(
                date.atTime(9, 45) to date.atTime(10, 15),
                date.atTime(9, 40) to date.atTime(10, 20),
                date.atTime(9, 0) to date.atTime(10, 30),
            )) {
            every { reservations.findCustomerOverlapping(any(), any(), any(), any()) } returns
                listOf(CustomerOccupiedInterval(start, end))
            assertEquals(
                listOf(true, true, false),
                service.getAvailableSlots(testId(7), testId(3), date, testId(12)).map { it.personalConflict },
            )
        }
    }

    @Test
    fun `anonymous and empty schedule requests do not read customer reservations`() {
        assertEquals(3, service.getAvailableSlots(testId(7), testId(3), date).size)
        every { weekly.findByCompanyIdAndEmployeeIdAndDayOfWeek(any(), any(), any()) } returns null
        assertTrue(service.getAvailableSlots(testId(7), testId(3), date, testId(12)).isEmpty())
        verify(exactly = 0) { reservations.findCustomerOverlapping(any(), any(), any(), any()) }
        verify(exactly = 0) { reservations.existsCustomerOverlapping(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `current overlapping Warsaw hour does not make future slot pricing ambiguous`() {
        company.lastMinuteDiscountPercent = 10
        val overlapClock = Clock.fixed(Instant.parse("2026-10-25T00:30:00Z"), ZoneOffset.UTC)
        val overlapService =
            AvailabilityService(
                offerings,
                companies,
                assignments,
                employees,
                conflicts,
                policy,
                overlapClock,
                reservations,
            )
        assertEquals(
            90,
            overlapService.getAvailableSlots(testId(7), testId(3), LocalDate.of(2026, 10, 25)).first().price,
        )
    }
}
