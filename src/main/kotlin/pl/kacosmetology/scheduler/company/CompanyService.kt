package pl.kacosmetology.scheduler.company

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.company.dto.CompanyEmployeeResponse
import pl.kacosmetology.scheduler.company.dto.CompanySettingsResponse
import pl.kacosmetology.scheduler.company.dto.UpdateCompanySettingsRequest
import pl.kacosmetology.scheduler.company.dto.toSettingsResponse
import pl.kacosmetology.scheduler.user.UserRepository

/** Business logic for reading and updating company settings (business hours, slot interval). */
@Service
class CompanyService(
    private val companyRepository: CompanyRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
    private val userRepository: UserRepository
) {

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
                id = ce.id!!,
                userId = ce.userId,
                firstName = user.firstName,
                lastName = user.lastName,
                role = ce.role
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

        if (!request.closingTime!!.isAfter(request.openingTime!!)) {
            throw IllegalArgumentException("Godzina zamknięcia musi być późniejsza niż godzina otwarcia")
        }

        val updated = companyRepository.save(
            Company(
                id = company.id,
                name = company.name,
                taxId = company.taxId,
                address = company.address,
                openingTime = request.openingTime,
                closingTime = request.closingTime,
                slotIntervalMinutes = request.slotIntervalMinutes
            )
        )
        return updated.toSettingsResponse()
    }
}
