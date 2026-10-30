package pl.kacosmetology.scheduler.availability

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.company.EmployeeEligibilityMode
import pl.kacosmetology.scheduler.company.effectivePrice
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.reservation.ReservationRepository

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
    private val reservationRepository: ReservationRepository,
) {

    /**
     * Lists the effective shift grid for an active qualified employee. Filters conflicts, invalid Warsaw times, elapsed
     * notice and the inclusive calendar horizon. Customer conflicts annotate otherwise free salon slots.
     */
    @Transactional(readOnly = true)
    fun getAvailableSlots(
        employeeId: UUID,
        offeringId: UUID,
        date: LocalDate,
        customerId: UUID? = null,
    ): List<AvailableSlotResponse> {
        val offering =
            offeringRepository.findById(offeringId).orElseThrow {
                NoSuchElementException("Usługa nie istnieje")
            }

        if (!offering.active) {
            throw NoSuchElementException("Usługa nie istnieje")
        }

        val company =
            companyRepository.findById(offering.companyId).orElseThrow {
                NoSuchElementException("Firma nie istnieje")
            }

        val employee =
            companyEmployeeRepository.findByCompanyIdAndUserId(offering.companyId, employeeId)?.takeIf { it.enabled }
                ?: throw NoSuchElementException("Pracownik nie istnieje")
        if (
            employee.eligibilityMode == EmployeeEligibilityMode.SPECIFIC_SERVICES &&
                !assignmentRepository.existsByCompanyIdAndEmployeeIdAndOfferingId(
                    offering.companyId,
                    employeeId,
                    offeringId,
                )
        ) {
            throw ApiException(ErrorCode.EMPLOYEE_OFFERING_NOT_ASSIGNED, "Ten pracownik nie wykonuje wybranej usługi")
        }
        val nowInstant = clock.instant()
        val now = LocalDateTime.ofInstant(nowInstant, BUSINESS_ZONE)
        if (date.isAfter(now.toLocalDate().plusDays(company.maxBookingAdvanceDays.toLong()))) return emptyList()

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
        val personalConflicts =
            if (customerId == null) emptyList()
            else
                reservationRepository
                    .findCustomerOverlapping(
                        offering.companyId,
                        customerId,
                        slotStarts.first(),
                        slotStarts.last().plusMinutes(requiredDuration),
                    )
                    .map {
                        EmployeeAvailabilityConflict(
                            EmployeeAvailabilityConflictSource.RESERVATION,
                            it.startTime,
                            it.endTime,
                        )
                    }
        val earliestBookableInstant = nowInstant.plusSeconds(company.minBookingAdvanceMinutes.toLong() * 60)

        return slotStarts
            .filter { start ->
                (if (company.minBookingAdvanceMinutes == 0) start.atZone(BUSINESS_ZONE).toInstant().isAfter(nowInstant)
                else !start.atZone(BUSINESS_ZONE).toInstant().isBefore(earliestBookableInstant)) &&
                    !employeeAvailabilityPolicy.overlapsAny(start, start.plusMinutes(requiredDuration), conflicts)
            }
            .map { start ->
                AvailableSlotResponse(
                    start.toLocalTime(),
                    company.effectivePrice(offering.price, start, nowInstant),
                    offering.price,
                    employeeAvailabilityPolicy.overlapsAny(
                        start,
                        start.plusMinutes(requiredDuration),
                        personalConflicts,
                    ),
                )
            }
    }
}
