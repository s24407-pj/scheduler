package pl.kacosmetology.scheduler.workschedule

import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalTime

/** A company employee's date-specific replacement shift or explicit day off. */
@Entity
@Table(name = "employee_schedule_overrides")
class EmployeeScheduleOverride(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    @Column(name = "company_id", nullable = false) val companyId: Long,
    @Column(name = "employee_id", nullable = false) val employeeId: Long,
    @Column(name = "schedule_date", nullable = false) val scheduleDate: LocalDate,
    @Column(name = "day_off", nullable = false) var dayOff: Boolean,
    @Column(name = "start_time") var startTime: LocalTime? = null,
    @Column(name = "end_time") var endTime: LocalTime? = null,
)
