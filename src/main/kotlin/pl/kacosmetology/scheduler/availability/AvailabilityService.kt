package pl.kacosmetology.scheduler.availability

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.company.effectivePrice
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.OfferingRepository

/** Calculates available time slots for a given employee and offering on a specific date. */
@Service
class AvailabilityService(
    private val offeringRepository: OfferingRepository,
    private val companyRepository: CompanyRepository,
    private val assignmentRepository: EmployeeOfferingAssignmentRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
    private val employeeAvailabilityPolicy: EmployeeAvailabilityPolicy,
    private val bookableSlotPolicy: BookableSlotPolicy,
    private val clock: Clock,
) {

    /**
     * Returns a list of available booking slots with pricing information.
     *
     * Candidate slots come from [BookableSlotPolicy] (the employee's work schedule at the offering's company and the
     * company slot interval); an empty list is returned if there is no schedule entry for that day. Throws
     * [ApiException] if the offering is unknown or inactive, the employee is not part of the offering's company, or has
     * offering assignments at that company and the requested offering is not among them. Filters out slots that overlap
     * with existing reservations, schedule blocks, and times not after now + minimum booking advance (per the injected
     * [Clock]). Applies the company's last-minute discount to slots starting within the configured time window.
     */
    @Transactional(readOnly = true)
    fun getAvailableSlots(employeeId: Long, offeringId: Long, date: LocalDate): List<AvailableSlotResponse> {
        val offering =
            offeringRepository.findById(offeringId).orElseThrow {
                ApiException(ErrorCode.OFFERING_UNAVAILABLE, "Usługa nie istnieje")
            }

        if (!offering.active) {
            throw ApiException(ErrorCode.OFFERING_UNAVAILABLE, "Ta usługa nie jest już dostępna")
        }

        val company =
            companyRepository.findById(offering.companyId).orElseThrow {
                IllegalArgumentException("Firma nie istnieje")
            }

        if (!companyEmployeeRepository.existsByCompanyIdAndUserId(offering.companyId, employeeId)) {
            throw ApiException(ErrorCode.EMPLOYEE_NOT_IN_COMPANY, "Pracownik nie należy do firmy wybranej usługi")
        }

        if (
            assignmentRepository.existsByCompanyIdAndEmployeeId(offering.companyId, employeeId) &&
                !assignmentRepository.existsByEmployeeIdAndOfferingId(employeeId, offeringId)
        ) {
            throw ApiException(ErrorCode.EMPLOYEE_OFFERING_NOT_ASSIGNED, "Ten pracownik nie wykonuje wybranej usługi")
        }

        val requiredDuration = offering.durationMinutes.toLong()
        val slotStarts =
            bookableSlotPolicy.slotStarts(
                offering.companyId,
                employeeId,
                date,
                offering.durationMinutes,
                company.slotIntervalMinutes,
            )
        if (slotStarts.isEmpty()) return emptyList()

        val conflicts =
            employeeAvailabilityPolicy.findConflicts(
                offering.companyId,
                employeeId,
                slotStarts.first(),
                slotStarts.last().plusMinutes(requiredDuration),
            )
        val now = LocalDateTime.now(clock)
        val earliestBookableTime = now.plusMinutes(company.minBookingAdvanceMinutes.toLong())

        return slotStarts
            .filter { start ->
                start.isAfter(earliestBookableTime) &&
                    !employeeAvailabilityPolicy.overlapsAny(start, start.plusMinutes(requiredDuration), conflicts)
            }
            .map { start ->
                AvailableSlotResponse(
                    start.toLocalTime(),
                    company.effectivePrice(offering.price, start, now),
                    offering.price,
                )
            }
    }
}
