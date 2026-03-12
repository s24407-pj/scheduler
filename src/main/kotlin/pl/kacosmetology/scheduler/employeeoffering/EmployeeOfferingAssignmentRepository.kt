package pl.kacosmetology.scheduler.employeeoffering

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/** JPA repository for [EmployeeOfferingAssignment] entities. */
@Repository
interface EmployeeOfferingAssignmentRepository : JpaRepository<EmployeeOfferingAssignment, Long> {
    /** Returns all offering assignments for the given employee. */
    fun findAllByEmployeeId(employeeId: Long): List<EmployeeOfferingAssignment>

    /** Returns true if the employee has any offering assignments. */
    fun existsByEmployeeId(employeeId: Long): Boolean

    /** Returns the assignment for the given employee and offering, or null if not found. */
    fun findByEmployeeIdAndOfferingId(employeeId: Long, offeringId: Long): EmployeeOfferingAssignment?

    /** Returns true if the employee is assigned to the given offering. */
    fun existsByEmployeeIdAndOfferingId(employeeId: Long, offeringId: Long): Boolean

    /** Removes a specific offering assignment for the given employee. */
    fun deleteByEmployeeIdAndOfferingId(employeeId: Long, offeringId: Long)

    /** Removes all offering assignments for the given employee. */
    fun deleteAllByEmployeeId(employeeId: Long)
}
