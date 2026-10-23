package pl.kacosmetology.scheduler.offering

import jakarta.persistence.LockModeType
import java.util.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/** JPA repository for [Offering] entities. */
@Repository
interface OfferingRepository : JpaRepository<Offering, UUID> {

    /** Returns an offering within its tenant. */
    fun findByIdAndCompanyId(id: UUID, companyId: UUID): Offering?

    /** Serializes gallery capacity decisions on the parent row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Offering o where o.id = :id and o.companyId = :companyId")
    fun findByIdAndCompanyIdForUpdate(id: UUID, companyId: UUID): Offering?

    /** Returns all offerings (including inactive) for the given company. */
    fun findAllByCompanyId(companyId: UUID): List<Offering>

    /** Returns only active offerings for the given company. Used by public and booking endpoints. */
    fun findAllByCompanyIdAndActiveTrue(companyId: UUID): List<Offering>
}
