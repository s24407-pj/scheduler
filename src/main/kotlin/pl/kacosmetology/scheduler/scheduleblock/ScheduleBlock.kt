package pl.kacosmetology.scheduler.scheduleblock

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.*
import org.hibernate.annotations.UuidGenerator

/** Represents company-scoped employee unavailability, such as a break or personal appointment. */
@Entity
@Table(name = "schedule_blocks")
class ScheduleBlock(
    id: UUID? = null,
    @Column(name = "company_id", nullable = false) val companyId: UUID,
    @Column(name = "employee_id", nullable = false) val employeeId: UUID,
    @Column(name = "start_time", nullable = false) var startTime: LocalDateTime,
    @Column(name = "end_time", nullable = false) var endTime: LocalDateTime,
    @Column(length = 255) val reason: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: LocalDateTime? = null,
) {
    @Version
    @Column(nullable = false)
    var version: Long = 0
        protected set

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = id
        protected set
}
