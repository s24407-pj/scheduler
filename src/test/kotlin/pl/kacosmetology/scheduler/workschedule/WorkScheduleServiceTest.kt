package pl.kacosmetology.scheduler.workschedule

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.testId
import pl.kacosmetology.scheduler.workschedule.dto.SetWeeklyScheduleRequest
import pl.kacosmetology.scheduler.workschedule.dto.WorkScheduleEntryRequest

@ExtendWith(MockKExtension::class)
class WorkScheduleServiceTest {

    @MockK private lateinit var workScheduleRepository: EmployeeWorkScheduleRepository

    @MockK private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @MockK private lateinit var companyRepository: CompanyRepository

    @MockK private lateinit var overrideRepository: EmployeeScheduleOverrideRepository

    @InjectMockKs private lateinit var workScheduleService: WorkScheduleService

    private val companyId = testId(1)
    private val employeeId = testId(10)

    @BeforeEach
    fun mockPolicyLocks() {
        every { companyRepository.findByIdForUpdate(companyId) } returns Company(id = companyId, name = "Firma")
        every { companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, employeeId) } returns
            CompanyEmployee(companyId = companyId, userId = employeeId, role = "EMPLOYEE")
    }

    @Test
    fun `setSchedule should save entries and return responses`() {
        val request =
            SetWeeklyScheduleRequest(
                entries =
                    listOf(
                        WorkScheduleEntryRequest(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                        WorkScheduleEntryRequest(DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(18, 0)),
                    ),
            )
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { workScheduleRepository.deleteAllByCompanyIdAndEmployeeId(companyId, employeeId) } returns Unit
        every { workScheduleRepository.flush() } returns Unit
        every { workScheduleRepository.saveAll(any<List<EmployeeWorkSchedule>>()) } answers
            {
                firstArg<List<EmployeeWorkSchedule>>()
            }

        val result = workScheduleService.setSchedule(companyId, employeeId, request)

        assertEquals(2, result.size)
        verify(exactly = 1) { workScheduleRepository.deleteAllByCompanyIdAndEmployeeId(companyId, employeeId) }
        verify(exactly = 1) { workScheduleRepository.saveAll(any<List<EmployeeWorkSchedule>>()) }
    }

    @Test
    fun `setSchedule with empty entries should clear schedule`() {
        val request = SetWeeklyScheduleRequest(entries = emptyList())
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { workScheduleRepository.deleteAllByCompanyIdAndEmployeeId(companyId, employeeId) } returns Unit
        every { workScheduleRepository.flush() } returns Unit
        every { workScheduleRepository.saveAll(any<List<EmployeeWorkSchedule>>()) } returns emptyList()

        val result = workScheduleService.setSchedule(companyId, employeeId, request)

        assertTrue(result.isEmpty())
        verify(exactly = 1) { workScheduleRepository.deleteAllByCompanyIdAndEmployeeId(companyId, employeeId) }
    }

    @Test
    fun `setSchedule with null entries rejects without changing saved schedule`() {
        assertThrows<IllegalArgumentException> {
            workScheduleService.setSchedule(companyId, employeeId, SetWeeklyScheduleRequest(entries = null))
        }
        verify(exactly = 0) { workScheduleRepository.deleteAllByCompanyIdAndEmployeeId(any(), any()) }
    }

    @Test
    fun `setSchedule should throw when duplicate days provided`() {
        val request =
            SetWeeklyScheduleRequest(
                entries =
                    listOf(
                        WorkScheduleEntryRequest(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                        WorkScheduleEntryRequest(DayOfWeek.MONDAY, LocalTime.of(10, 0), LocalTime.of(18, 0)),
                    ),
            )
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true

        val ex =
            assertThrows<IllegalArgumentException> {
                workScheduleService.setSchedule(companyId, employeeId, request)
            }
        assertEquals("Grafik zawiera zduplikowane dni tygodnia", ex.message)
        verify(exactly = 0) { workScheduleRepository.deleteAllByCompanyIdAndEmployeeId(any(), any()) }
    }

    @Test
    fun `setSchedule should throw when endTime is not after startTime`() {
        val request =
            SetWeeklyScheduleRequest(
                entries =
                    listOf(
                        WorkScheduleEntryRequest(DayOfWeek.FRIDAY, LocalTime.of(17, 0), LocalTime.of(9, 0)),
                    ),
            )
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true

        assertThrows<IllegalArgumentException> {
            workScheduleService.setSchedule(companyId, employeeId, request)
        }
        verify(exactly = 0) { workScheduleRepository.deleteAllByCompanyIdAndEmployeeId(any(), any()) }
    }

    @Test
    fun `setSchedule should throw when employee does not belong to company`() {
        val request = SetWeeklyScheduleRequest(entries = emptyList())
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns false
        every { companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, employeeId) } returns null

        assertThrows<NoSuchElementException> {
            workScheduleService.setSchedule(companyId, employeeId, request)
        }
    }

    @Test
    fun `getSchedule should throw when employee does not belong to company`() {
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns false

        assertThrows<NoSuchElementException> {
            workScheduleService.getSchedule(companyId, employeeId)
        }
    }
}
