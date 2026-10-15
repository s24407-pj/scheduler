package pl.kacosmetology.scheduler.company

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface CompanyEmployeeRepository : JpaRepository<CompanyEmployee, Long> {

    /** Finds exactly one company-owned staff login namespace. */
    fun findByCompanyIdAndLoginEmail(companyId: Long, loginEmail: String): CompanyEmployee?

    /** Reads a public employee identifier inside its company. */
    fun findByCompanyIdAndUserId(companyId: Long, userId: Long): CompanyEmployee?

    /** Checks object references before deleting a shared legacy photo. */
    fun existsByPhotoUrl(photoUrl: String): Boolean

    /** Allocates independent public employee identifiers without creating customer accounts. */
    @Query(value = "SELECT nextval('staff_public_id_seq')", nativeQuery = true) fun nextPublicId(): Long

    /** Serializes credential validation with password reset/disable. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM CompanyEmployee e WHERE e.companyId = :companyId AND e.loginEmail = :email")
    fun findLoginForUpdate(companyId: Long, email: String): CompanyEmployee?

    /** Locks a staff account independently from its public identifier. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM CompanyEmployee e WHERE e.id = :id")
    fun findAccountForUpdate(id: Long): CompanyEmployee?

    fun findAllByUserId(userId: Long): List<CompanyEmployee>

    fun findAllByCompanyId(companyId: Long): List<CompanyEmployee>

    fun existsByCompanyIdAndUserId(companyId: Long, userId: Long): Boolean

    /**
     * Locks one employment row until the surrounding transaction completes. Used to serialize reservation and
     * schedule-block availability mutations for the same employee and company.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ce FROM CompanyEmployee ce WHERE ce.companyId = :companyId AND ce.userId = :userId")
    fun findByCompanyIdAndUserIdForUpdate(companyId: Long, userId: Long): CompanyEmployee?
}
