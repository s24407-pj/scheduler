package pl.kacosmetology.scheduler.offering

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/** JPA repository for [Offering] entities. */
@Repository
interface OfferingRepository : JpaRepository<Offering, Long> {

    /** Returns all offerings (including inactive) for the given company. */
    fun findAllByCompanyId(companyId: Long): List<Offering>

    /** Returns only active offerings for the given company. Used by public and booking endpoints. */
    fun findAllByCompanyIdAndActiveTrue(companyId: Long): List<Offering>
}
