package pl.kacosmetology.scheduler.workschedule

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.workschedule.dto.SetWeeklyScheduleRequest
import pl.kacosmetology.scheduler.workschedule.dto.WorkScheduleEntryResponse
import pl.kacosmetology.scheduler.workschedule.dto.toResponse

/** Business logic for managing employee weekly work schedules. */
@Service
class WorkScheduleService(
    private val workScheduleRepository: EmployeeWorkScheduleRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository
) {

    /** Returns the full weekly schedule for the given employee. */
    @Transactional(readOnly = true)
    fun getSchedule(employeeId: Long): List<WorkScheduleEntryResponse> =
        workScheduleRepository.findAllByEmployeeId(employeeId).map { it.toResponse() }

    /**
     * Atomically replaces the employee's weekly schedule.
     * An empty or null [SetWeeklyScheduleRequest.entries] list clears all entries.
     * Throws [NoSuchElementException] if the employee does not belong to [companyId].
     * Throws [IllegalArgumentException] for duplicate days or invalid time ranges.
     */
    @Transactional
    fun setSchedule(
        companyId: Long,
        employeeId: Long,
        request: SetWeeklyScheduleRequest
    ): List<WorkScheduleEntryResponse> {
        if (!companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId)) {
            throw NoSuchElementException("Pracownik nie należy do tej firmy")
        }

        val entries = request.entries ?: emptyList()

        val days = entries.mapNotNull { it.dayOfWeek }
        if (days.size != days.toSet().size) {
            throw IllegalArgumentException("Grafik zawiera zduplikowane dni tygodnia")
        }

        for (entry in entries) {
            val day = entry.dayOfWeek
                ?: throw IllegalArgumentException("Dzień tygodnia jest wymagany")
            val start = entry.startTime
                ?: throw IllegalArgumentException("Godzina rozpoczęcia jest wymagana dla $day")
            val end = entry.endTime
                ?: throw IllegalArgumentException("Godzina zakończenia jest wymagana dla $day")
            if (!end.isAfter(start)) {
                throw IllegalArgumentException("Godzina zakończenia musi być późniejsza niż godzina rozpoczęcia ($day)")
            }
        }

        workScheduleRepository.deleteAllByEmployeeId(employeeId)

        val saved = workScheduleRepository.saveAll(
            entries.map { entry ->
                EmployeeWorkSchedule(
                    companyId = companyId,
                    employeeId = employeeId,
                    dayOfWeek = entry.dayOfWeek!!,
                    startTime = entry.startTime!!,
                    endTime = entry.endTime!!
                )
            }
        )

        return saved.map { it.toResponse() }
    }
}
