package pl.kacosmetology.scheduler.user

import org.springframework.data.jpa.repository.JpaRepository

/** Repository for company-scoped customer block records. */
interface CompanyCustomerBlockRepository : JpaRepository<CompanyCustomerBlock, Long> {
    /** Returns the block record for the given company/customer pair, or null if none exists. */
    fun findByCompanyIdAndCustomerId(companyId: Long, customerId: Long): CompanyCustomerBlock?
}
