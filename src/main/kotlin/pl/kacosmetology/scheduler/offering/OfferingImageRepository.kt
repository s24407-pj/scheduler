package pl.kacosmetology.scheduler.offering

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

/** Repository for [OfferingImage] entities. */
interface OfferingImageRepository : JpaRepository<OfferingImage, UUID> {
    fun findAllByOfferingIdIn(offeringIds: List<UUID>): List<OfferingImage>

    fun countByOfferingId(offeringId: UUID): Int
}
