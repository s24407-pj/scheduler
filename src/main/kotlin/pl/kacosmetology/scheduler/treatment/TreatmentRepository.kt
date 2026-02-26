package pl.kacosmetology.scheduler.treatment

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TreatmentRepository : JpaRepository<ProvidedService, Long> {

    fun findAllByCompanyId(companyId: Long): List<ProvidedService>

    fun findAllByCompanyIdAndActiveTrue(companyId: Long): List<ProvidedService>
}