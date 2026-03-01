package pl.kacosmetology.scheduler.treatment

import org.springframework.data.jpa.repository.JpaRepository

/** Repository for [ServiceImage] entities. */
interface ServiceImageRepository : JpaRepository<ServiceImage, Long> {
    fun findAllByServiceIdIn(serviceIds: List<Long>): List<ServiceImage>
    fun countByServiceId(serviceId: Long): Int
}
