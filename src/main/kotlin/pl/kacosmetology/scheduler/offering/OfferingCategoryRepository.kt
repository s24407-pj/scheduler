package pl.kacosmetology.scheduler.offering

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/** JPA repository for [OfferingCategory] entities. */
@Repository
interface OfferingCategoryRepository : JpaRepository<OfferingCategory, UUID> {
    /** Returns all categories belonging to the given company. */
    fun findAllByCompanyId(companyId: UUID): List<OfferingCategory>

    /** Returns true if a category with the given name already exists in the company. */
    fun existsByCompanyIdAndName(companyId: UUID, name: String): Boolean
}
