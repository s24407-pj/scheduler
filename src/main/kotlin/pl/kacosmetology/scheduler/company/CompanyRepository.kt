package pl.kacosmetology.scheduler.company

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/** JPA repository for [Company] entities. */
@Repository
interface CompanyRepository : JpaRepository<Company, Long>

