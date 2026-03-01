package pl.kacosmetology.scheduler.employeeservice

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/** JPA repository for [EmployeeServiceAssignment] entities. */
@Repository
interface EmployeeServiceAssignmentRepository : JpaRepository<EmployeeServiceAssignment, Long> {
    /** Returns all service assignments for the given employee. */
    fun findAllByEmployeeId(employeeId: Long): List<EmployeeServiceAssignment>

    /** Returns true if the employee has any service assignments. */
    fun existsByEmployeeId(employeeId: Long): Boolean

    /** Returns true if the employee is assigned to the given service. */
    fun existsByEmployeeIdAndServiceId(employeeId: Long, serviceId: Long): Boolean

    /** Removes a specific service assignment for the given employee. */
    fun deleteByEmployeeIdAndServiceId(employeeId: Long, serviceId: Long)

    /** Removes all service assignments for the given employee. */
    fun deleteAllByEmployeeId(employeeId: Long)
}
