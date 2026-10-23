package pl.kacosmetology.scheduler.workschedule

import java.time.DayOfWeek
import java.util.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/** JPA repository for [EmployeeWorkSchedule] entities. Schedules are scoped to one (company, employee) pair. */
@Repository
interface EmployeeWorkScheduleRepository : JpaRepository<EmployeeWorkSchedule, UUID> {
    /** Returns all schedule entries for the given employee at [companyId]. */
    fun findAllByCompanyIdAndEmployeeId(companyId: UUID, employeeId: UUID): List<EmployeeWorkSchedule>

    /** Returns the schedule entry for a specific day at [companyId], or null if the employee doesn't work that day. */
    fun findByCompanyIdAndEmployeeIdAndDayOfWeek(
        companyId: UUID,
        employeeId: UUID,
        dayOfWeek: DayOfWeek,
    ): EmployeeWorkSchedule?

    /**
     * Deletes all schedule entries for the given employee at [companyId]. Used in atomic replace during
     * [WorkScheduleService.setSchedule].
     */
    fun deleteAllByCompanyIdAndEmployeeId(companyId: UUID, employeeId: UUID)
}
