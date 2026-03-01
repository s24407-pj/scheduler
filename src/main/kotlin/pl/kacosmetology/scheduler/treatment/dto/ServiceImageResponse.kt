package pl.kacosmetology.scheduler.treatment.dto

import pl.kacosmetology.scheduler.treatment.ServiceImage

/** DTO returned in API responses representing a single service image. */
data class ServiceImageResponse(
    val id: Long,
    val imageUrl: String
) {
    companion object {
        fun from(image: ServiceImage) = ServiceImageResponse(
            id = image.id!!,
            imageUrl = image.imageUrl
        )
    }
}
