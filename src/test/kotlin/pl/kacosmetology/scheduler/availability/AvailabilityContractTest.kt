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
import pl.kacosmetology.scheduler.offering.*
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.workschedule.*

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
    private val company = Company(id = 1, name = "Warszawa")
    private val employee = CompanyEmployee(companyId = 1, userId = 7, role = "EMPLOYEE")
    private val policy = BookableSlotPolicy(weekly, overrides)
    private val service =
        AvailabilityService(offerings, companies, assignments, employees, conflicts, policy, clock, reservations)

    @BeforeEach
    fun setup() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        every { offerings.findById(3) } returns
            Optional.of(Offering(id = 3, companyId = 1, name = "Usługa", durationMinutes = 30, price = 100))
        every { companies.findById(1) } returns Optional.of(company)
        every { employees.findByCompanyIdAndUserId(1, 7) } returns employee
        every { assignments.existsByCompanyIdAndEmployeeIdAndOfferingId(1, 7, 3) } returns false
        every { overrides.findByCompanyIdAndEmployeeIdAndScheduleDate(1, 7, any()) } returns null
        every { weekly.findByCompanyIdAndEmployeeIdAndDayOfWeek(1, 7, any()) } answers
            {
                EmployeeWorkSchedule(
                    companyId = 1,
                    employeeId = 7,
                    dayOfWeek = thirdArg(),
                    startTime = LocalTime.of(9, 0),
                    endTime = LocalTime.of(11, 0),
                )
            }
        every { conflicts.findConflicts(any(), any(), any(), any()) } returns emptyList()
        every { conflicts.overlapsAny(any(), any(), any()) } returns false
        every { reservations.existsCustomerOverlapping(any(), any(), any(), any(), any()) } returns false
    }

    @AfterEach
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `zero notice is strictly future in Warsaw even with UTC JVM and UTC clock`() {
        val slots = service.getAvailableSlots(7, 3, date)
        assertEquals(LocalTime.of(9, 30), slots.first().time)
        assertEquals(3, slots.size)
    }

    @Test
    fun `positive notice exact elapsed boundary is inclusive`() {
        company.minBookingAdvanceMinutes = 30
        assertEquals(LocalTime.of(9, 30), service.getAvailableSlots(7, 3, date).first().time)
    }

    @Test
    fun `entire horizon final Warsaw calendar date is bookable`() {
        every { weekly.findByCompanyIdAndEmployeeIdAndDayOfWeek(1, 7, any()) } answers
            {
                EmployeeWorkSchedule(
                    companyId = 1,
                    employeeId = 7,
                    dayOfWeek = thirdArg(),
                    startTime = LocalTime.of(22, 0),
                    endTime = LocalTime.of(23, 59),
                )
            }
        assertEquals(LocalTime.of(23, 0), service.getAvailableSlots(7, 3, date.plusDays(90)).last().time)
        assertTrue(service.getAvailableSlots(7, 3, date.plusDays(91)).isEmpty())
    }

    @Test
    fun `specific empty qualification permits no service while disabled account is opaque`() {
        employee.eligibilityMode = EmployeeEligibilityMode.SPECIFIC_SERVICES
        assertEquals(
            ErrorCode.EMPLOYEE_OFFERING_NOT_ASSIGNED,
            assertThrows<ApiException> { service.getAvailableSlots(7, 3, date) }.code,
        )
        employee.enabled = false
        assertThrows<NoSuchElementException> { service.getAvailableSlots(7, 3, date) }
    }

    @Test
    fun `personal conflicts mark otherwise free slots without hiding them from salon availability`() {
        every { reservations.existsCustomerOverlapping(1, 12, date.atTime(9, 30), date.atTime(10, 0), -1) } returns true
        val personal = service.getAvailableSlots(7, 3, date, 12)
        val public = service.getAvailableSlots(7, 3, date)
        assertEquals(public.map { it.time }, personal.map { it.time })
        assertTrue(personal.first().personalConflict)
        assertFalse(public.any { it.personalConflict })
        verify(exactly = 0) { reservations.existsCustomerOverlapping(2, any(), any(), any(), any()) }
    }
}
