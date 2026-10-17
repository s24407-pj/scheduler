package pl.kacosmetology.scheduler.user

import jakarta.persistence.*
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** Tracks per-company no-show count and block status for a customer. */
@Entity
@Table(name = "company_customer_blocks")
class CompanyCustomerBlock(
    id: UUID? = null,
    @Column(name = "company_id", nullable = false) val companyId: UUID,
    @Column(name = "customer_id", nullable = false) val customerId: UUID,
    @Column(name = "no_show_count", nullable = false) var noShowCount: Int = 0,
    @Column(nullable = false) var blocked: Boolean = false,
    @Column(name = "manual_blocked", nullable = false) var manualBlocked: Boolean = false,
    @Column(name = "automatic_blocked", nullable = false) var automaticBlocked: Boolean = false,
) {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = id
        protected set
}
