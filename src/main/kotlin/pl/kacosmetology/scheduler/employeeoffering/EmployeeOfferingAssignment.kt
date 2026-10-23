package pl.kacosmetology.scheduler.employeeoffering

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.*
import org.hibernate.annotations.UuidGenerator

/** Associates an employee with an offering they are allowed to perform. */
@Entity
@Table(name = "employee_offerings")
class EmployeeOfferingAssignment(
    id: UUID? = null,
    @Column(name = "company_id", nullable = false) val companyId: UUID,
    @Column(name = "employee_id", nullable = false) val employeeId: UUID,
    @Column(name = "offering_id", nullable = false) val offeringId: UUID,
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    val createdAt: OffsetDateTime? = null,
) {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = id
        protected set
}
