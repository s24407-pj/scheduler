package pl.kacosmetology.scheduler.user

import java.util.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import pl.kacosmetology.scheduler.reservation.Reservation

/** Repository for company-scoped customer data (notes, etc.). */
interface CompanyCustomerRepository : JpaRepository<CompanyCustomer, UUID> {
    fun findByCompanyIdAndUserId(companyId: UUID, userId: UUID): CompanyCustomer?

    fun findByCompanyId(companyId: UUID): List<CompanyCustomer>

    /** Reads visit history only inside the legitimate salon/customer relationship. */
    @Query(
        "SELECT r FROM Reservation r WHERE r.companyId = :companyId AND r.customerId = :customerId AND r.archived = false ORDER BY r.startTime DESC",
    )
    fun findVisitHistory(companyId: UUID, customerId: UUID): List<Reservation>
}
