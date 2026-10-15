package pl.kacosmetology.scheduler.employeeoffering

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.employeeoffering.dto.EmployeeOfferingAssignmentResponse
import pl.kacosmetology.scheduler.employeeoffering.dto.toResponse
import pl.kacosmetology.scheduler.offering.OfferingRepository

/** Business logic for managing which offerings an employee is allowed to perform. */
@Service
class EmployeeOfferingService(
    private val assignmentRepository: EmployeeOfferingAssignmentRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
    private val offeringRepository: OfferingRepository,
) {

    /**
     * Returns all offering assignments for the given employee at [companyId]. Throws [NoSuchElementException] if the
     * employee is not in the company.
     */
    @Transactional(readOnly = true)
    fun getAssignments(companyId: Long, employeeId: Long): List<EmployeeOfferingAssignmentResponse> {
        if (!companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId)) {
            throw NoSuchElementException("Pracownik nie należy do tej firmy")
        }
        return assignmentRepository.findAllByCompanyIdAndEmployeeId(companyId, employeeId).map { it.toResponse() }
    }

    /**
     * Assigns an offering to an employee. Idempotent — if already assigned, returns the existing record. Throws
     * [NoSuchElementException] if the employee is not in the company or the offering does not exist. Throws
     * [IllegalStateException] if the offering belongs to a different company.
     */
    @Transactional
    fun assignOffering(companyId: Long, employeeId: Long, offeringId: Long): EmployeeOfferingAssignmentResponse {
        if (!companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId)) {
            throw NoSuchElementException("Pracownik nie należy do tej firmy")
        }
        val offering =
            offeringRepository.findById(offeringId).orElseThrow { NoSuchElementException("Usługa nie istnieje") }
        if (offering.companyId != companyId) {
            throw NoSuchElementException("Usługa nie istnieje")
        }
        assignmentRepository.findByEmployeeIdAndOfferingId(employeeId, offeringId)?.let {
            return it.toResponse()
        }
        return assignmentRepository
            .save(
                EmployeeOfferingAssignment(companyId = companyId, employeeId = employeeId, offeringId = offeringId),
            )
            .toResponse()
    }

    /**
     * Removes an offering assignment from an employee. Throws [NoSuchElementException] if the employee is not in the
     * company or the assignment does not exist.
     */
    @Transactional
    fun removeAssignment(companyId: Long, employeeId: Long, offeringId: Long) {
        if (!companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId)) {
            throw NoSuchElementException("Pracownik nie należy do tej firmy")
        }
        offeringRepository.findByIdAndCompanyId(offeringId, companyId)
            ?: throw NoSuchElementException("Usługa nie istnieje")
        if (!assignmentRepository.existsByCompanyIdAndEmployeeIdAndOfferingId(companyId, employeeId, offeringId)) {
            throw NoSuchElementException("Przypisanie nie istnieje")
        }
        assignmentRepository.deleteByCompanyIdAndEmployeeIdAndOfferingId(companyId, employeeId, offeringId)
    }
}
