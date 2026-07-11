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
