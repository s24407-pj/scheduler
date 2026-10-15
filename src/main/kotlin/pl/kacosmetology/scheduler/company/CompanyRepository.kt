package pl.kacosmetology.scheduler.company

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/** JPA repository for [Company] entities. */
@Repository
interface CompanyRepository : JpaRepository<Company, Long> {
    /** Serializes booking allocation and mutable company booking policies in a single-instance salon. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Company c WHERE c.id = :id")
    fun findByIdForUpdate(id: Long): Company?
}
