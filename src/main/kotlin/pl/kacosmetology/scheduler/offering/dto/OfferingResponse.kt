package pl.kacosmetology.scheduler.offering.dto

import java.time.LocalDateTime
import java.util.UUID
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingImage

/**
 * API response DTO for a salon offering. Mirrors all fields of [Offering] and adds [images] from the `offering_images`
 * table.
 */
data class OfferingResponse(
    val id: UUID?,
    val companyId: UUID,
    val name: String,
    val durationMinutes: Int,
    val price: Int,
    val active: Boolean,
    val categoryId: UUID?,
    val createdAt: LocalDateTime?,
    val images: List<OfferingImageResponse>,
) {
    companion object {
        fun from(offering: Offering, images: List<OfferingImage>) =
            OfferingResponse(
                id = offering.id,
                companyId = offering.companyId,
                name = offering.name,
                durationMinutes = offering.durationMinutes,
                price = offering.price,
                active = offering.active,
                categoryId = offering.categoryId,
                createdAt = offering.createdAt,
                images = images.map { OfferingImageResponse.from(it) },
            )
    }
}
