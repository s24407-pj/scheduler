package pl.kacosmetology.scheduler.company

import jakarta.persistence.LockModeType
import java.util.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface CompanyEmployeeRepository : JpaRepository<CompanyEmployee, UUID> {

    /** Bounded, indexed cross-company discovery; credentials are reloaded under a row lock. */
    @Query("SELECT e.id FROM CompanyEmployee e WHERE e.loginEmail = :email ORDER BY e.id")
    fun findLoginCandidates(email: String, pageable: org.springframework.data.domain.Pageable): List<UUID>

    /** Scalar projection avoids retaining stale credential entities in the request persistence context. */
    @Query(
        """SELECT new pl.kacosmetology.scheduler.auth.StaffCredentialSnapshot(
        e.id, e.companyId, e.userId, e.loginEmail, e.passwordHash, e.enabled, e.role, e.authVersion, 0L
    ) FROM CompanyEmployee e WHERE e.id = :id""",
    )
    fun findCredentialSnapshot(id: UUID): pl.kacosmetology.scheduler.auth.StaffCredentialSnapshot?

    /** Finds exactly one company-owned staff login namespace. */
    fun findByCompanyIdAndLoginEmail(companyId: UUID, loginEmail: String): CompanyEmployee?

    /** Reads a public employee identifier inside its company. */
    fun findByCompanyIdAndUserId(companyId: UUID, userId: UUID): CompanyEmployee?

    /** Checks object references before deleting a shared legacy photo. */
    fun existsByPhotoUrl(photoUrl: String): Boolean

    /** Allocates independent public employee identifiers without creating customer accounts. */
    @Query(value = "SELECT uuidv7()", nativeQuery = true) fun nextPublicId(): UUID

    /** Serializes credential validation with password reset/disable. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM CompanyEmployee e WHERE e.companyId = :companyId AND e.loginEmail = :email")
    fun findLoginForUpdate(companyId: UUID, email: String): CompanyEmployee?

    /** Locks a staff account independently from its public identifier. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM CompanyEmployee e WHERE e.id = :id")
    fun findAccountForUpdate(id: UUID): CompanyEmployee?

    fun findAllByCompanyId(companyId: UUID): List<CompanyEmployee>

    fun existsByCompanyIdAndUserId(companyId: UUID, userId: UUID): Boolean

    /**
     * Locks one employment row until the surrounding transaction completes. Used to serialize reservation and
     * schedule-block availability mutations for the same employee and company.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ce FROM CompanyEmployee ce WHERE ce.companyId = :companyId AND ce.userId = :userId")
    fun findByCompanyIdAndUserIdForUpdate(companyId: UUID, userId: UUID): CompanyEmployee?
}
