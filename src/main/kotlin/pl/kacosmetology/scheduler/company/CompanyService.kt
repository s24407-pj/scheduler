package pl.kacosmetology.scheduler.company

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.company.dto.CompanyEmployeeResponse
import pl.kacosmetology.scheduler.company.dto.CompanyPublicProfileResponse
import pl.kacosmetology.scheduler.company.dto.CompanySettingsResponse
import pl.kacosmetology.scheduler.company.dto.PublicEmployeeResponse
import pl.kacosmetology.scheduler.company.dto.UpdateCompanyProfileRequest
import pl.kacosmetology.scheduler.company.dto.UpdateCompanySettingsRequest
import pl.kacosmetology.scheduler.company.dto.toPublicProfileResponse
import pl.kacosmetology.scheduler.company.dto.toSettingsResponse
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.OfferingRepository

/** Business logic for reading and updating company settings (business hours, slot interval). */
@Service
class CompanyService(
    private val companyRepository: CompanyRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
    private val offeringRepository: OfferingRepository,
    private val assignmentRepository: EmployeeOfferingAssignmentRepository,
) {

    /** Returns public company profile for customer booking. Throws [NoSuchElementException] if not found. */
    @Transactional(readOnly = true)
    fun getPublicProfile(companyId: Long): CompanyPublicProfileResponse {
        val company = companyRepository.findById(companyId).orElseThrow { NoSuchElementException("Firma nie istnieje") }
        return company.toPublicProfileResponse()
    }

    /** Returns active employees eligible to perform [offeringId] at [companyId], using explicit qualification modes. */
    @Transactional(readOnly = true)
    fun getPublicBookableEmployees(companyId: Long, offeringId: Long): List<PublicEmployeeResponse> {
        companyRepository.findById(companyId).orElseThrow { NoSuchElementException("Firma nie istnieje") }
        val offering =
            offeringRepository.findById(offeringId).orElseThrow { NoSuchElementException("Usługa nie istnieje") }
        if (offering.companyId != companyId) {
            throw NoSuchElementException("Usługa nie istnieje")
        }
        if (!offering.active) {
            throw NoSuchElementException("Usługa nie istnieje")
        }

        return companyEmployeeRepository
            .findAllByCompanyId(companyId)
            .filter {
                it.enabled &&
                    (it.eligibilityMode == EmployeeEligibilityMode.ALL_SERVICES ||
                        assignmentRepository.existsByCompanyIdAndEmployeeIdAndOfferingId(
                            companyId,
                            it.userId,
                            offeringId,
                        ))
            }
            .map { PublicEmployeeResponse(it.userId, it.firstName, it.lastName, it.photoUrl) }
    }

    /** Returns company settings. Throws [NoSuchElementException] if the company does not exist. */
    @Transactional(readOnly = true)
    fun getCompany(companyId: Long): CompanySettingsResponse {
        val company = companyRepository.findById(companyId).orElseThrow { NoSuchElementException("Firma nie istnieje") }
        return company.toSettingsResponse()
    }

    /**
     * Returns all employees (OWNER + EMPLOYEE roles) of the given company. Throws [NoSuchElementException] if any user
     * record is missing (data integrity guard).
     */
    @Transactional(readOnly = true)
    fun getEmployees(companyId: Long): List<CompanyEmployeeResponse> {
        return companyEmployeeRepository.findAllByCompanyId(companyId).map { ce ->
            CompanyEmployeeResponse(
                requireNotNull(ce.id),
                ce.userId,
                ce.firstName,
                ce.lastName,
                ce.role,
                ce.photoUrl,
                ce.loginEmail,
                ce.contactPhone,
                ce.enabled,
                ce.eligibilityMode,
            )
        }
    }

    /**
     * Updates company business hours and slot interval. Throws [IllegalArgumentException] if [closingTime] is not
     * strictly after [openingTime].
     */
    @Transactional
    fun updateSettings(companyId: Long, request: UpdateCompanySettingsRequest): CompanySettingsResponse {
        val company =
            companyRepository.findByIdForUpdate(companyId) ?: throw NoSuchElementException("Firma nie istnieje")

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
        company.maxBookingAdvanceDays = request.maxBookingAdvanceDays
        company.maxFutureCustomerBookings = request.maxFutureCustomerBookings
        return company.toSettingsResponse()
    }

    /** Updates only the authenticated owner's company-local public presentation. */
    @Transactional
    fun updateProfile(companyId: Long, request: UpdateCompanyProfileRequest): CompanyPublicProfileResponse {
        val company =
            companyRepository.findByIdForUpdate(companyId) ?: throw NoSuchElementException("Firma nie istnieje")
        company.name = request.name.trim()
        company.address = request.address?.trim()?.takeIf { it.isNotEmpty() }
        return company.toPublicProfileResponse()
    }
}
