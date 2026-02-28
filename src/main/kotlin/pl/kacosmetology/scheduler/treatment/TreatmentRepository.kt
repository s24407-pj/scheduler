package pl.kacosmetology.scheduler.treatment

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/** JPA repository for [ProvidedService] entities. */
@Repository
interface TreatmentRepository : JpaRepository<ProvidedService, Long> {

    /** Returns all services (including inactive) for the given company. */
    fun findAllByCompanyId(companyId: Long): List<ProvidedService>

    /** Returns only active services for the given company. Used by public and booking endpoints. */
    fun findAllByCompanyIdAndActiveTrue(companyId: Long): List<ProvidedService>
}