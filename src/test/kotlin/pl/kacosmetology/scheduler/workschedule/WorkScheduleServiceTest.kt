package pl.kacosmetology.scheduler.workschedule

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.workschedule.dto.SetWeeklyScheduleRequest
import pl.kacosmetology.scheduler.workschedule.dto.WorkScheduleEntryRequest
import java.time.DayOfWeek
import java.time.LocalTime

@ExtendWith(MockKExtension::class)
class WorkScheduleServiceTest {

    @MockK
    private lateinit var workScheduleRepository: EmployeeWorkScheduleRepository

    @MockK
    private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @InjectMockKs
    private lateinit var workScheduleService: WorkScheduleService

    private val companyId = 1L
    private val employeeId = 10L

    @Test
    fun `setSchedule should save entries and return responses`() {
        val request = SetWeeklyScheduleRequest(
            entries = listOf(
                WorkScheduleEntryRequest(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                WorkScheduleEntryRequest(DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(18, 0))
            )
        )
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { workScheduleRepository.deleteAllByEmployeeId(employeeId) } returns Unit
        every { workScheduleRepository.saveAll(any<List<EmployeeWorkSchedule>>()) } answers {
            firstArg<List<EmployeeWorkSchedule>>()
        }

        val result = workScheduleService.setSchedule(companyId, employeeId, request)

        assertEquals(2, result.size)
        verify(exactly = 1) { workScheduleRepository.deleteAllByEmployeeId(employeeId) }
        verify(exactly = 1) { workScheduleRepository.saveAll(any<List<EmployeeWorkSchedule>>()) }
    }

    @Test
    fun `setSchedule with empty entries should clear schedule`() {
        val request = SetWeeklyScheduleRequest(entries = emptyList())
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { workScheduleRepository.deleteAllByEmployeeId(employeeId) } returns Unit
        every { workScheduleRepository.saveAll(any<List<EmployeeWorkSchedule>>()) } returns emptyList()

        val result = workScheduleService.setSchedule(companyId, employeeId, request)

        assertTrue(result.isEmpty())
        verify(exactly = 1) { workScheduleRepository.deleteAllByEmployeeId(employeeId) }
    }

    @Test
    fun `setSchedule with null entries should clear schedule`() {
        val request = SetWeeklyScheduleRequest(entries = null)
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every { workScheduleRepository.deleteAllByEmployeeId(employeeId) } returns Unit
        every { workScheduleRepository.saveAll(any<List<EmployeeWorkSchedule>>()) } returns emptyList()

        val result = workScheduleService.setSchedule(companyId, employeeId, request)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `setSchedule should throw when duplicate days provided`() {
        val request = SetWeeklyScheduleRequest(
            entries = listOf(
                WorkScheduleEntryRequest(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                WorkScheduleEntryRequest(DayOfWeek.MONDAY, LocalTime.of(10, 0), LocalTime.of(18, 0))
            )
        )
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true

        val ex = assertThrows<IllegalArgumentException> {
            workScheduleService.setSchedule(companyId, employeeId, request)
        }
        assertEquals("Grafik zawiera zduplikowane dni tygodnia", ex.message)
        verify(exactly = 0) { workScheduleRepository.deleteAllByEmployeeId(any()) }
    }

    @Test
    fun `setSchedule should throw when endTime is not after startTime`() {
        val request = SetWeeklyScheduleRequest(
            entries = listOf(
                WorkScheduleEntryRequest(DayOfWeek.FRIDAY, LocalTime.of(17, 0), LocalTime.of(9, 0))
            )
        )
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true

        assertThrows<IllegalArgumentException> {
            workScheduleService.setSchedule(companyId, employeeId, request)
        }
        verify(exactly = 0) { workScheduleRepository.deleteAllByEmployeeId(any()) }
    }

    @Test
    fun `setSchedule should throw when employee does not belong to company`() {
        val request = SetWeeklyScheduleRequest(entries = emptyList())
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns false

        assertThrows<NoSuchElementException> {
            workScheduleService.setSchedule(companyId, employeeId, request)
        }
    }
}
