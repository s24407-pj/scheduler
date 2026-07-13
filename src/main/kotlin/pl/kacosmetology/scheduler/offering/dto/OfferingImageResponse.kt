package pl.kacosmetology.scheduler.offering.dto

import pl.kacosmetology.scheduler.offering.OfferingImage

/** DTO returned in API responses representing a single offering image. */
data class OfferingImageResponse(
    val id: Long,
    val imageUrl: String
) {
    companion object {
        fun from(image: OfferingImage) = OfferingImageResponse(
            id = requireNotNull(image.id) { "Persisted offering image must have an ID" },
            imageUrl = image.imageUrl
        )
    }
}
