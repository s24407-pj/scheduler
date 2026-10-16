package pl.kacosmetology.scheduler.workschedule

import java.time.LocalDate
import org.springframework.data.jpa.repository.JpaRepository

/** Tenant-scoped date-specific schedule storage. */
interface EmployeeScheduleOverrideRepository : JpaRepository<EmployeeScheduleOverride, Long> {
    /** Returns a replacement or day off for the requested employee and date. */
    fun findByCompanyIdAndEmployeeIdAndScheduleDate(
        companyId: Long,
        employeeId: Long,
        scheduleDate: LocalDate,
    ): EmployeeScheduleOverride?

    /** Lists explicit overrides in an inclusive date range. */
    fun findAllByCompanyIdAndEmployeeIdAndScheduleDateBetweenOrderByScheduleDate(
        companyId: Long,
        employeeId: Long,
        start: LocalDate,
        end: LocalDate,
    ): List<EmployeeScheduleOverride>
}
