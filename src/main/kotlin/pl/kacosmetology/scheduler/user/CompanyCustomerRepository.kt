package pl.kacosmetology.scheduler.user

import java.util.*
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import pl.kacosmetology.scheduler.reservation.Reservation

/** Repository for company-scoped customer data (notes, etc.). */
interface CompanyCustomerRepository : JpaRepository<CompanyCustomer, UUID> {
    fun findByCompanyIdAndId(companyId: UUID, id: UUID): CompanyCustomer?

    fun findByCompanyIdAndPhoneNumber(companyId: UUID, phoneNumber: String): CompanyCustomer?

    fun findByCompanyIdAndIdAndFirstBookingAtIsNotNull(companyId: UUID, id: UUID): CompanyCustomer?

    fun findByCompanyIdAndFirstBookingAtIsNotNull(companyId: UUID): List<CompanyCustomer>

    fun findByCompanyIdAndFirstBookingAtIsNotNull(companyId: UUID, pageable: Pageable): Slice<CompanyCustomer>

    fun findByCompanyIdAndIdIn(companyId: UUID, ids: Collection<UUID>): List<CompanyCustomer>

    /** A concurrent first login or staff booking must converge on the same local identity. */
    @Modifying
    @Query(
        value =
            "INSERT INTO company_customers(company_id,phone_number) VALUES(:companyId,:phoneNumber) ON CONFLICT(company_id,phone_number) DO NOTHING",
        nativeQuery = true,
    )
    fun insertIfPhoneAbsent(companyId: UUID, phoneNumber: String): Int

    fun findByCompanyId(companyId: UUID): List<CompanyCustomer>

    /** Reads visit history only inside the legitimate salon/customer relationship. */
    @Query(
        "SELECT r FROM Reservation r WHERE r.companyId = :companyId AND r.customerId = :customerId AND r.archived = false ORDER BY r.startTime DESC,r.id DESC",
    )
    fun findVisitHistory(companyId: UUID, customerId: UUID, pageable: Pageable): Slice<Reservation>
}
