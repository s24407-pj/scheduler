package pl.kacosmetology.scheduler.employeeoffering.dto

import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignment

/** Response body for an employee–offering assignment. */
data class EmployeeOfferingAssignmentResponse(
    val offeringId: Long,
    val employeeId: Long
)

/** Maps an [EmployeeOfferingAssignment] entity to an [EmployeeOfferingAssignmentResponse] DTO. */
fun EmployeeOfferingAssignment.toResponse() = EmployeeOfferingAssignmentResponse(
    offeringId = offeringId,
    employeeId = employeeId
)
