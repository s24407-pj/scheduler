package pl.kacosmetology.scheduler.workschedule

import jakarta.persistence.*
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.OffsetDateTime

/** Represents a single day's working hours for an employee. One entry per [dayOfWeek] per employee. */
@Entity
@Table(name = "employee_work_schedules")
class EmployeeWorkSchedule(
    id: Long? = null,

    @Column(name = "company_id", nullable = false)
    val companyId: Long,

    @Column(name = "employee_id", nullable = false)
    val employeeId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 10)
    val dayOfWeek: DayOfWeek,

    @Column(name = "start_time", nullable = false)
    val startTime: LocalTime,

    @Column(name = "end_time", nullable = false)
    val endTime: LocalTime,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: OffsetDateTime? = null
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set
}
