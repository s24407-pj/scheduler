package pl.kacosmetology.scheduler.treatment

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/** JPA repository for [ServiceCategory] entities. */
@Repository
interface ServiceCategoryRepository : JpaRepository<ServiceCategory, Long> {
    /** Returns all categories belonging to the given company. */
    fun findAllByCompanyId(companyId: Long): List<ServiceCategory>

    /** Returns true if a category with the given name already exists in the company. */
    fun existsByCompanyIdAndName(companyId: Long, name: String): Boolean
}
