package pl.kacosmetology.scheduler.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import pl.kacosmetology.scheduler.reservation.Reservation

/** Repository for company-scoped customer data (notes, etc.). */
interface CompanyCustomerRepository : JpaRepository<CompanyCustomer, Long> {
    fun findByCompanyIdAndUserId(companyId: Long, userId: Long): CompanyCustomer?

    fun findByCompanyId(companyId: Long): List<CompanyCustomer>

    /** Reads visit history only inside the legitimate salon/customer relationship. */
    @Query(
        "SELECT r FROM Reservation r WHERE r.companyId = :companyId AND r.customerId = :customerId AND r.archived = false ORDER BY r.startTime DESC",
    )
    fun findVisitHistory(companyId: Long, customerId: Long): List<Reservation>
}
