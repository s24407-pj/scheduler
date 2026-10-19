package pl.kacosmetology.scheduler.company

import jakarta.persistence.LockModeType
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/** JPA repository for [Company] entities. */
@Repository
interface CompanyRepository : JpaRepository<Company, UUID> {
    /** Finds the development fixture company by its stable seed name. */
    fun findByName(name: String): Company?

    /** Serializes booking allocation and mutable company booking policies in a single-instance salon. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Company c WHERE c.id = :id")
    fun findByIdForUpdate(id: UUID): Company?
}
