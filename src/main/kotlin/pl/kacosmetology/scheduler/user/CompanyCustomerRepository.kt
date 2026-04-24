package pl.kacosmetology.scheduler.user

import org.springframework.data.jpa.repository.JpaRepository

/** Repository for company-scoped customer data (notes, etc.). */
interface CompanyCustomerRepository : JpaRepository<CompanyCustomer, Long> {
    fun findByCompanyIdAndUserId(companyId: Long, userId: Long): CompanyCustomer?
    fun findByCompanyId(companyId: Long): List<CompanyCustomer>
}
