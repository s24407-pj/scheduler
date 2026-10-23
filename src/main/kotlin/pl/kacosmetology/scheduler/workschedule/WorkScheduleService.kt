package pl.kacosmetology.scheduler.workschedule

import java.time.LocalDate
import java.util.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.workschedule.dto.*

/** Business logic for managing employee weekly work schedules. */
@Service
class WorkScheduleService(
    private val workScheduleRepository: EmployeeWorkScheduleRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
    private val companyRepository: CompanyRepository,
    private val overrideRepository: EmployeeScheduleOverrideRepository,
) {

    /**
     * Returns the full weekly schedule for the given employee at [companyId]. Throws [NoSuchElementException] if the
     * employee does not belong to [companyId].
     */
    @Transactional(readOnly = true)
    fun getSchedule(companyId: UUID, employeeId: UUID): List<WorkScheduleEntryResponse> {
        if (!companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId)) {
            throw NoSuchElementException("Pracownik nie należy do tej firmy")
        }
        return workScheduleRepository.findAllByCompanyIdAndEmployeeId(companyId, employeeId).map { it.toResponse() }
    }

    /**
     * Atomically replaces the employee's weekly schedule at [companyId]; schedules at other companies are untouched.
     * Only an explicit empty [SetWeeklyScheduleRequest.entries] list clears all entries. Throws
     * [NoSuchElementException] if the employee does not belong to [companyId]. Throws [IllegalArgumentException] for
     * duplicate days or invalid time ranges.
     */
    @Transactional
    fun setSchedule(
        companyId: UUID,
        employeeId: UUID,
        request: SetWeeklyScheduleRequest,
    ): List<WorkScheduleEntryResponse> {
        lockPolicies(companyId, employeeId)
        val entries = requireNotNull(request.entries) { "Lista wpisów grafiku jest wymagana" }

        val days = entries.mapNotNull { it.dayOfWeek }
        if (days.size != days.toSet().size) {
            throw IllegalArgumentException("Grafik zawiera zduplikowane dni tygodnia")
        }

        val validatedEntries = entries.map { entry ->
            val day = entry.dayOfWeek ?: throw IllegalArgumentException("Dzień tygodnia jest wymagany")
            val start = entry.startTime ?: throw IllegalArgumentException("Godzina rozpoczęcia jest wymagana dla $day")
            val end = entry.endTime ?: throw IllegalArgumentException("Godzina zakończenia jest wymagana dla $day")
            if (!end.isAfter(start)) {
                throw IllegalArgumentException("Godzina zakończenia musi być późniejsza niż godzina rozpoczęcia ($day)")
            }
            Triple(day, start, end)
        }

        workScheduleRepository.deleteAllByCompanyIdAndEmployeeId(companyId, employeeId)
        workScheduleRepository.flush()

        val saved =
            workScheduleRepository.saveAll(
                validatedEntries.map { (day, start, end) ->
                    EmployeeWorkSchedule(
                        companyId = companyId,
                        employeeId = employeeId,
                        dayOfWeek = day,
                        startTime = start,
                        endTime = end,
                    )
                },
            )

        return saved.map { it.toResponse() }
    }

    /** Lists tenant-owned overrides for an employee within an inclusive date range. */
    @Transactional(readOnly = true)
    fun getOverrides(
        companyId: UUID,
        employeeId: UUID,
        start: LocalDate,
        end: LocalDate,
    ): List<ScheduleOverrideResponse> {
        require(!end.isBefore(start)) { "Koniec zakresu nie może poprzedzać początku" }
        if (!companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId))
            throw NoSuchElementException("Pracownik nie istnieje")
        return overrideRepository
            .findAllByCompanyIdAndEmployeeIdAndScheduleDateBetweenOrderByScheduleDate(companyId, employeeId, start, end)
            .map { it.toResponse() }
    }

    /** Sets a date's replacement shift or day off under the same policy lock as booking. */
    @Transactional
    fun setOverride(
        companyId: UUID,
        employeeId: UUID,
        date: LocalDate,
        request: ScheduleOverrideRequest,
    ): ScheduleOverrideResponse {
        lockPolicies(companyId, employeeId)
        require(request.dayOff != null && request.isValidShift()) { "Nieprawidłowa zmiana" }
        val override =
            overrideRepository.findByCompanyIdAndEmployeeIdAndScheduleDate(companyId, employeeId, date)
                ?: EmployeeScheduleOverride(
                    companyId = companyId,
                    employeeId = employeeId,
                    scheduleDate = date,
                    dayOff = request.dayOff,
                )
        override.dayOff = request.dayOff
        override.startTime = request.startTime
        override.endTime = request.endTime
        return overrideRepository.save(override).toResponse()
    }

    /** Removes an override, restoring the weekly schedule's behavior for that date. */
    @Transactional
    fun deleteOverride(companyId: UUID, employeeId: UUID, date: LocalDate) {
        lockPolicies(companyId, employeeId)
        val override =
            overrideRepository.findByCompanyIdAndEmployeeIdAndScheduleDate(companyId, employeeId, date) ?: return
        overrideRepository.delete(override)
    }

    private fun lockPolicies(companyId: UUID, employeeId: UUID) {
        companyRepository.findByIdForUpdate(companyId) ?: throw NoSuchElementException("Firma nie istnieje")
        companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, employeeId)
            ?: throw NoSuchElementException("Pracownik nie istnieje")
    }
}
