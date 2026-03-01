package pl.kacosmetology.scheduler.employeeservice

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.employeeservice.dto.EmployeeServiceAssignmentResponse
import pl.kacosmetology.scheduler.employeeservice.dto.toResponse
import pl.kacosmetology.scheduler.treatment.ProvidedService
import pl.kacosmetology.scheduler.treatment.TreatmentRepository

/** Business logic for managing which services an employee is allowed to perform. */
@Service
class EmployeeServiceManagementService(
    private val assignmentRepository: EmployeeServiceAssignmentRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
    private val treatmentRepository: TreatmentRepository
) {

    /** Returns all service assignments for the given employee. */
    @Transactional(readOnly = true)
    fun getAssignments(employeeId: Long): List<EmployeeServiceAssignmentResponse> =
        assignmentRepository.findAllByEmployeeId(employeeId).map { it.toResponse() }

    /**
     * Returns active services assigned to the given employee.
     * Returns an empty list if the employee has no assignments configured.
     */
    @Transactional(readOnly = true)
    fun getServicesForEmployee(employeeId: Long): List<ProvidedService> {
        if (!assignmentRepository.existsByEmployeeId(employeeId)) return emptyList()
        return assignmentRepository.findAllByEmployeeId(employeeId)
            .mapNotNull { treatmentRepository.findById(it.serviceId).orElse(null) }
            .filter { it.active }
    }

    /**
     * Assigns a service to an employee. Idempotent — if already assigned, returns the existing record.
     * Throws [NoSuchElementException] if the employee is not in the company or the service does not exist.
     * Throws [IllegalStateException] if the service belongs to a different company.
     */
    @Transactional
    fun assignService(companyId: Long, employeeId: Long, serviceId: Long): EmployeeServiceAssignmentResponse {
        if (!companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId)) {
            throw NoSuchElementException("Pracownik nie należy do tej firmy")
        }
        val service = treatmentRepository.findById(serviceId)
            .orElseThrow { NoSuchElementException("Usługa nie istnieje") }
        if (service.companyId != companyId) {
            throw IllegalStateException("Usługa nie należy do tej firmy")
        }
        if (assignmentRepository.existsByEmployeeIdAndServiceId(employeeId, serviceId)) {
            return assignmentRepository.findAllByEmployeeId(employeeId)
                .first { it.serviceId == serviceId }.toResponse()
        }
        return assignmentRepository.save(
            EmployeeServiceAssignment(companyId = companyId, employeeId = employeeId, serviceId = serviceId)
        ).toResponse()
    }

    /**
     * Removes a service assignment from an employee.
     * Throws [NoSuchElementException] if the employee is not in the company or the assignment does not exist.
     */
    @Transactional
    fun removeAssignment(companyId: Long, employeeId: Long, serviceId: Long) {
        if (!companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId)) {
            throw NoSuchElementException("Pracownik nie należy do tej firmy")
        }
        if (!assignmentRepository.existsByEmployeeIdAndServiceId(employeeId, serviceId)) {
            throw NoSuchElementException("Przypisanie nie istnieje")
        }
        assignmentRepository.deleteByEmployeeIdAndServiceId(employeeId, serviceId)
    }
}
