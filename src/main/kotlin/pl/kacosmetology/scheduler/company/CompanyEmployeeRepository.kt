package pl.kacosmetology.scheduler.company

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface CompanyEmployeeRepository : JpaRepository<CompanyEmployee, Long> {

    fun findAllByUserId(userId: Long): List<CompanyEmployee>

    fun findAllByCompanyId(companyId: Long): List<CompanyEmployee>

    fun existsByCompanyIdAndUserId(companyId: Long, userId: Long): Boolean

    /**
     * Locks one employment row until the surrounding transaction completes.
     * Used to serialize reservation and schedule-block availability mutations for the same employee and company.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ce FROM CompanyEmployee ce WHERE ce.companyId = :companyId AND ce.userId = :userId")
    fun findByCompanyIdAndUserIdForUpdate(companyId: Long, userId: Long): CompanyEmployee?
}

