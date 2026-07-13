package pl.kacosmetology.scheduler.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.jdbc.core.JdbcTemplate

/** Repository for company-scoped customer block records. */
interface CompanyCustomerBlockRepository :
    JpaRepository<CompanyCustomerBlock, Long>,
    CompanyCustomerBlockRepositoryCustom {
    /** Returns the block record for the given company/customer pair, or null if none exists. */
    fun findByCompanyIdAndCustomerId(companyId: Long, customerId: Long): CompanyCustomerBlock?
    fun findByCompanyId(companyId: Long): List<CompanyCustomerBlock>
}

/** Custom atomic operations for company-scoped customer block records. */
interface CompanyCustomerBlockRepositoryCustom {
    /** Atomically increments the no-show count and applies the company's automatic blocking threshold. */
    fun incrementNoShowCountAndApplyBlock(companyId: Long, customerId: Long): Int
}

/** PostgreSQL implementation of the custom customer block operations. */
class CompanyCustomerBlockRepositoryImpl(
    private val jdbcTemplate: JdbcTemplate
) : CompanyCustomerBlockRepositoryCustom {
    /**
     * Creates the tracking row if necessary or increments the existing row under PostgreSQL's conflict-row lock.
     * The returned value is the count after the increment.
     */
    override fun incrementNoShowCountAndApplyBlock(companyId: Long, customerId: Long): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                INSERT INTO company_customer_blocks (company_id, customer_id, no_show_count, blocked)
                SELECT id, ?, 1, max_no_shows > 0 AND 1 >= max_no_shows
                FROM companies
                WHERE id = ?
                ON CONFLICT (company_id, customer_id) DO UPDATE
                SET no_show_count = company_customer_blocks.no_show_count + 1,
                    blocked = company_customer_blocks.blocked OR EXISTS (
                        SELECT 1
                        FROM companies
                        WHERE id = company_customer_blocks.company_id
                          AND max_no_shows > 0
                          AND company_customer_blocks.no_show_count + 1 >= max_no_shows
                    )
                RETURNING no_show_count
                """.trimIndent(),
                Int::class.java,
                customerId,
                companyId
            )
        ) { "Firma nie istnieje" }
}
