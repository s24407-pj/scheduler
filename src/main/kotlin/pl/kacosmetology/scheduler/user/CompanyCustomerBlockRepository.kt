package pl.kacosmetology.scheduler.user

import java.util.*
import org.springframework.data.jpa.repository.JpaRepository

/** Repository for company-scoped customer block records. */
interface CompanyCustomerBlockRepository : JpaRepository<CompanyCustomerBlock, UUID> {
    /** Returns the block record for the given company/customer pair, or null if none exists. */
    fun findByCompanyIdAndCustomerId(companyId: UUID, customerId: UUID): CompanyCustomerBlock?

    fun findByCompanyId(companyId: UUID): List<CompanyCustomerBlock>
}
