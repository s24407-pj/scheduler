package pl.kacosmetology.scheduler.employeeoffering

import jakarta.persistence.*
import java.time.OffsetDateTime

/** Associates an employee with an offering they are allowed to perform. */
@Entity
@Table(name = "employee_offerings")
class EmployeeOfferingAssignment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "company_id", nullable = false)
    val companyId: Long,

    @Column(name = "employee_id", nullable = false)
    val employeeId: Long,

    @Column(name = "offering_id", nullable = false)
    val offeringId: Long,

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: OffsetDateTime? = null
)
