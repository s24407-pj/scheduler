package pl.kacosmetology.scheduler.employeeoffering

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.employeeoffering.dto.EmployeeOfferingAssignmentResponse
import pl.kacosmetology.scheduler.employeeoffering.dto.toResponse
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository

/** Business logic for managing which offerings an employee is allowed to perform. */
@Service
class EmployeeOfferingService(
    private val assignmentRepository: EmployeeOfferingAssignmentRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
    private val offeringRepository: OfferingRepository
) {

    /** Returns all offering assignments for the given employee. */
    @Transactional(readOnly = true)
    fun getAssignments(employeeId: Long): List<EmployeeOfferingAssignmentResponse> =
        assignmentRepository.findAllByEmployeeId(employeeId).map { it.toResponse() }

    /**
     * Returns active offerings assigned to the given employee.
     * Returns an empty list if the employee has no assignments configured.
     */
    @Transactional(readOnly = true)
    fun getOfferingsForEmployee(employeeId: Long): List<Offering> {
        if (!assignmentRepository.existsByEmployeeId(employeeId)) return emptyList()
        return assignmentRepository.findAllByEmployeeId(employeeId)
            .mapNotNull { offeringRepository.findById(it.offeringId).orElse(null) }
            .filter { it.active }
    }

    /**
     * Assigns an offering to an employee. Idempotent — if already assigned, returns the existing record.
     * Throws [NoSuchElementException] if the employee is not in the company or the offering does not exist.
     * Throws [IllegalStateException] if the offering belongs to a different company.
     */
    @Transactional
    fun assignOffering(companyId: Long, employeeId: Long, offeringId: Long): EmployeeOfferingAssignmentResponse {
        if (!companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId)) {
            throw NoSuchElementException("Pracownik nie należy do tej firmy")
        }
        val offering = offeringRepository.findById(offeringId)
            .orElseThrow { NoSuchElementException("Usługa nie istnieje") }
        if (offering.companyId != companyId) {
            throw IllegalStateException("Usługa nie należy do tej firmy")
        }
        if (assignmentRepository.existsByEmployeeIdAndOfferingId(employeeId, offeringId)) {
            return assignmentRepository.findAllByEmployeeId(employeeId)
                .first { it.offeringId == offeringId }.toResponse()
        }
        return assignmentRepository.save(
            EmployeeOfferingAssignment(companyId = companyId, employeeId = employeeId, offeringId = offeringId)
        ).toResponse()
    }

    /**
     * Removes an offering assignment from an employee.
     * Throws [NoSuchElementException] if the employee is not in the company or the assignment does not exist.
     */
    @Transactional
    fun removeAssignment(companyId: Long, employeeId: Long, offeringId: Long) {
        if (!companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId)) {
            throw NoSuchElementException("Pracownik nie należy do tej firmy")
        }
        if (!assignmentRepository.existsByEmployeeIdAndOfferingId(employeeId, offeringId)) {
            throw NoSuchElementException("Przypisanie nie istnieje")
        }
        assignmentRepository.deleteByEmployeeIdAndOfferingId(employeeId, offeringId)
    }
}
