package pl.kacosmetology.scheduler.employeeservice.dto

import pl.kacosmetology.scheduler.employeeservice.EmployeeServiceAssignment

/** Response body for an employee–service assignment. */
data class EmployeeServiceAssignmentResponse(
    val serviceId: Long,
    val employeeId: Long
)

/** Maps an [EmployeeServiceAssignment] entity to a [EmployeeServiceAssignmentResponse] DTO. */
fun EmployeeServiceAssignment.toResponse() = EmployeeServiceAssignmentResponse(
    serviceId = serviceId,
    employeeId = employeeId
)
