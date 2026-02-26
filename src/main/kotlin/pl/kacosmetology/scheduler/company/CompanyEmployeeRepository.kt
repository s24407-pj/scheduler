package pl.kacosmetology.scheduler.company

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CompanyEmployeeRepository : JpaRepository<CompanyEmployee, Long> {

    fun findAllByUserId(userId: Long): List<CompanyEmployee>

    fun existsByCompanyIdAndUserId(companyId: Long, userId: Long): Boolean
}

