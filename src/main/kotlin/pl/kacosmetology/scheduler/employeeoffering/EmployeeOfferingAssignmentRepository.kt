package pl.kacosmetology.scheduler.employeeoffering

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/** JPA repository for [EmployeeOfferingAssignment] entities. */
@Repository
interface EmployeeOfferingAssignmentRepository : JpaRepository<EmployeeOfferingAssignment, UUID> {
    /** Returns all offering assignments for the given employee, across all companies. */
    fun findAllByEmployeeId(employeeId: UUID): List<EmployeeOfferingAssignment>

    /** Returns all offering assignments for the given employee at [companyId]. */
    fun findAllByCompanyIdAndEmployeeId(companyId: UUID, employeeId: UUID): List<EmployeeOfferingAssignment>

    /** Returns true if the employee has any offering assignments at [companyId]. */
    fun existsByCompanyIdAndEmployeeId(companyId: UUID, employeeId: UUID): Boolean

    /** Returns the assignment for the given employee and offering, or null if not found. */
    fun findByEmployeeIdAndOfferingId(employeeId: UUID, offeringId: UUID): EmployeeOfferingAssignment?

    /** Returns true if the employee is assigned to the given offering. */
    fun existsByEmployeeIdAndOfferingId(employeeId: UUID, offeringId: UUID): Boolean

    /** Removes an assignment only within its company. */
    fun deleteByCompanyIdAndEmployeeIdAndOfferingId(companyId: UUID, employeeId: UUID, offeringId: UUID)

    /** Returns whether the company owns this assignment. */
    fun existsByCompanyIdAndEmployeeIdAndOfferingId(companyId: UUID, employeeId: UUID, offeringId: UUID): Boolean

    /** Removes all offering assignments for the given employee. */
    fun deleteAllByEmployeeId(employeeId: UUID)
}
