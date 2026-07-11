package pl.kacosmetology.scheduler.user

import jakarta.persistence.*

/** Tracks per-company no-show count and block status for a customer. */
@Entity
@Table(name = "company_customer_blocks")
class CompanyCustomerBlock(
    id: Long? = null,

    @Column(name = "company_id", nullable = false)
    val companyId: Long,

    @Column(name = "customer_id", nullable = false)
    val customerId: Long,

    @Column(name = "no_show_count", nullable = false)
    var noShowCount: Int = 0,

    @Column(nullable = false)
    var blocked: Boolean = false
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set
}
