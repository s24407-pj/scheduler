package pl.kacosmetology.scheduler.treatment.dto

import pl.kacosmetology.scheduler.treatment.ProvidedService
import pl.kacosmetology.scheduler.treatment.ServiceImage
import java.time.LocalDateTime

/**
 * API response DTO for a salon service.
 * Mirrors all fields of [ProvidedService] and adds [images] from the `service_images` table.
 */
data class ProvidedServiceResponse(
    val id: Long?,
    val companyId: Long,
    val name: String,
    val durationMinutes: Int,
    val price: Int,
    val active: Boolean,
    val categoryId: Long?,
    val createdAt: LocalDateTime?,
    val images: List<ServiceImageResponse>
) {
    companion object {
        fun from(service: ProvidedService, images: List<ServiceImage>) = ProvidedServiceResponse(
            id = service.id,
            companyId = service.companyId,
            name = service.name,
            durationMinutes = service.durationMinutes,
            price = service.price,
            active = service.active,
            categoryId = service.categoryId,
            createdAt = service.createdAt,
            images = images.map { ServiceImageResponse.from(it) }
        )
    }
}
