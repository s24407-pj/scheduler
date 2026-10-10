package pl.kacosmetology.scheduler.company

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.company.dto.CompanyEmployeeResponse
import pl.kacosmetology.scheduler.company.dto.CompanyPublicProfileResponse
import pl.kacosmetology.scheduler.company.dto.CompanySettingsResponse
import pl.kacosmetology.scheduler.company.dto.PublicEmployeeResponse
import pl.kacosmetology.scheduler.company.dto.UpdateCompanySettingsRequest
import pl.kacosmetology.scheduler.company.dto.toPublicProfileResponse
import pl.kacosmetology.scheduler.company.dto.toSettingsResponse
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.user.UserRepository

/** Business logic for reading and updating company settings (business hours, slot interval). */
@Service
class CompanyService(
    private val companyRepository: CompanyRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
    private val userRepository: UserRepository,
    private val offeringRepository: OfferingRepository,
    private val assignmentRepository: EmployeeOfferingAssignmentRepository
) {

    /** Returns public company profile for customer booking. Throws [NoSuchElementException] if not found. */
    @Transactional(readOnly = true)
    fun getPublicProfile(companyId: Long): CompanyPublicProfileResponse {
        val company = companyRepository.findById(companyId)
            .orElseThrow { NoSuchElementException("Firma nie istnieje") }
        return company.toPublicProfileResponse()
    }

    /**
     * Returns employees eligible to perform [offeringId] at [companyId].
     * Employees without any offering assignments are eligible for every offering in their company.
     */
    @Transactional(readOnly = true)
    fun getPublicBookableEmployees(companyId: Long, offeringId: Long): List<PublicEmployeeResponse> {
        companyRepository.findById(companyId)
            .orElseThrow { NoSuchElementException("Firma nie istnieje") }
        val offering = offeringRepository.findById(offeringId)
            .orElseThrow { NoSuchElementException("Usługa nie istnieje") }
        if (offering.companyId != companyId) {
            throw NoSuchElementException("Usługa nie istnieje")
        }
        if (!offering.active) {
            throw NoSuchElementException("Usługa nie istnieje")
        }

        val memberships = companyEmployeeRepository.findAllByCompanyId(companyId)
        val eligibleUserIds = memberships
            .filter { emp ->
                !assignmentRepository.existsByCompanyIdAndEmployeeId(companyId, emp.userId) ||
                    assignmentRepository.existsByEmployeeIdAndOfferingId(emp.userId, offeringId)
            }
            .map { it.userId }

        if (eligibleUserIds.isEmpty()) return emptyList()

        val usersById = userRepository.findAllById(eligibleUserIds).associateBy { it.id }
        return eligibleUserIds.mapNotNull { userId ->
            usersById[userId]?.let { user ->
                PublicEmployeeResponse(
                    id = userId,
                    firstName = user.firstName,
                    lastName = user.lastName,
                    photoUrl = user.photoUrl
                )
            }
        }
    }

    /** Returns company settings. Throws [NoSuchElementException] if the company does not exist. */
    @Transactional(readOnly = true)
    fun getCompany(companyId: Long): CompanySettingsResponse {
        val company = companyRepository.findById(companyId)
            .orElseThrow { NoSuchElementException("Firma nie istnieje") }
        return company.toSettingsResponse()
    }

    /**
     * Returns all employees (OWNER + EMPLOYEE roles) of the given company.
     * Throws [NoSuchElementException] if any user record is missing (data integrity guard).
     */
    @Transactional(readOnly = true)
    fun getEmployees(companyId: Long): List<CompanyEmployeeResponse> {
        val memberships = companyEmployeeRepository.findAllByCompanyId(companyId)
        val userIds = memberships.map { it.userId }.toSet()
        val usersById = userRepository.findAllById(userIds).associateBy { it.id }
        return memberships.map { ce ->
            val user = usersById[ce.userId]
                ?: throw NoSuchElementException("Użytkownik ${ce.userId} nie istnieje")
            CompanyEmployeeResponse(
                id = requireNotNull(ce.id) { "Persisted employment must have an ID" },
                userId = ce.userId,
                firstName = user.firstName,
                lastName = user.lastName,
                role = ce.role,
                photoUrl = user.photoUrl
            )
        }
    }

    /**
     * Updates company business hours and slot interval.
     * Throws [IllegalArgumentException] if [closingTime] is not strictly after [openingTime].
     */
    @Transactional
    fun updateSettings(companyId: Long, request: UpdateCompanySettingsRequest): CompanySettingsResponse {
        val company = companyRepository.findById(companyId)
            .orElseThrow { NoSuchElementException("Firma nie istnieje") }

        val openingTime = requireNotNull(request.openingTime) { "Godzina otwarcia jest wymagana" }
        val closingTime = requireNotNull(request.closingTime) { "Godzina zamknięcia jest wymagana" }
        if (!closingTime.isAfter(openingTime)) {
            throw IllegalArgumentException("Godzina zamknięcia musi być późniejsza niż godzina otwarcia")
        }

        company.openingTime = openingTime
        company.closingTime = closingTime
        company.slotIntervalMinutes = request.slotIntervalMinutes
        company.maxNoShows = request.maxNoShows
        company.lastMinuteDiscountPercent = request.lastMinuteDiscountPercent
        company.lastMinuteDiscountHours = request.lastMinuteDiscountHours
        company.minBookingAdvanceMinutes = request.minBookingAdvanceMinutes
        return company.toSettingsResponse()
    }
}
