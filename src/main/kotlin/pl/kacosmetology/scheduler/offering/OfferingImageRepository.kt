package pl.kacosmetology.scheduler.offering

import org.springframework.data.jpa.repository.JpaRepository

/** Repository for [OfferingImage] entities. */
interface OfferingImageRepository : JpaRepository<OfferingImage, Long> {
    fun findAllByOfferingIdIn(offeringIds: List<Long>): List<OfferingImage>
    fun countByOfferingId(offeringId: Long): Int
}
