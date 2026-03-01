package pl.kacosmetology.scheduler.treatment

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.treatment.dto.ProvidedServiceResponse
import pl.kacosmetology.scheduler.treatment.dto.TreatmentRequest

/**
 * REST API for managing salon services (treatments).
 * Public read endpoints are available without authentication; write operations require the OWNER role.
 */
@RestController
@RequestMapping("/api/services")
class TreatmentController(
    private val treatmentService: TreatmentService,
    private val imageService: ImageService
) {

    /** Returns all active services for a given company. Public — no authentication required. */
    @GetMapping("/public/company/{companyId}")
    fun getPublicCompanyServices(@PathVariable companyId: Long): List<ProvidedServiceResponse> {
        val services = treatmentService.getCompanyServices(companyId)
        return enrichWithImages(services)
    }

    /** Returns all services (including inactive) for a company. Requires staff membership in the same company. */
    @GetMapping("/company/{companyId}")
    fun getCompanyServices(
        @PathVariable companyId: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): List<ProvidedServiceResponse> {
        if (userDetails.companyId != companyId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak dostępu do tej firmy")
        }
        val services = treatmentService.getAllCompanyServices(companyId)
        return enrichWithImages(services)
    }

    /** Returns a single service by ID. Requires staff membership in the same company as the service. */
    @GetMapping("/{id}")
    fun getServiceById(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ProvidedServiceResponse {
        val service = findServiceOrThrow(id)
        if (service.companyId != userDetails.companyId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak dostępu do tej usługi")
        }
        return enrichWithImages(service)
    }

    /** Creates a new service for the authenticated user's company. Requires OWNER role. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER')")
    fun createService(
        @Valid @RequestBody request: TreatmentRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ProvidedServiceResponse {
        val service = treatmentService.createService(requireCompanyId(userDetails), request)
        return enrichWithImages(service)
    }

    /** Updates an existing service. Requires OWNER role. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER')")
    fun updateService(
        @PathVariable id: Long,
        @Valid @RequestBody request: TreatmentRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ProvidedServiceResponse {
        return try {
            val service = treatmentService.updateService(id, requireCompanyId(userDetails), request)
            enrichWithImages(service)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message)
        }
    }

    /** Reactivates a previously deactivated service. Requires OWNER role. */
    @PatchMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER')")
    fun activateService(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) {
        try {
            treatmentService.activateService(id, requireCompanyId(userDetails))
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message)
        }
    }

    /** Soft-deletes a service by marking it as inactive. Requires OWNER role. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER')")
    fun deleteService(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ) {
        try {
            treatmentService.deleteService(id, requireCompanyId(userDetails))
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message)
        }
    }

    /**
     * Uploads an image for the service (multipart field `image`).
     * Max 5 images per service; max 5 MB per file; allowed types: JPEG, PNG, WebP.
     * Requires OWNER role.
     */
    @PostMapping("/{id}/image")
    @PreAuthorize("hasAnyRole('OWNER')")
    fun uploadImage(
        @PathVariable id: Long,
        @RequestParam("image") file: MultipartFile,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ProvidedServiceResponse {
        findServiceOrThrow(id) // throws 404 before file validation runs
        return try {
            val service = treatmentService.uploadImage(id, requireCompanyId(userDetails), file)
            enrichWithImages(service)
        } catch (e: IllegalArgumentException) {
            // Only file-validation errors reach here (type / size)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message)
        }
    }

    /**
     * Deletes one image from the service by [imageId].
     * Requires OWNER role.
     */
    @DeleteMapping("/{id}/image/{imageId}")
    @PreAuthorize("hasAnyRole('OWNER')")
    fun deleteImage(
        @PathVariable id: Long,
        @PathVariable imageId: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ProvidedServiceResponse {
        return try {
            val service = treatmentService.deleteImage(id, imageId, requireCompanyId(userDetails))
            enrichWithImages(service)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message)
        }
    }

    // ---- helpers ----

    private fun requireCompanyId(userDetails: CustomUserDetails): Long =
        userDetails.companyId
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisania do firmy")

    private fun findServiceOrThrow(id: Long): ProvidedService =
        try {
            treatmentService.getServiceById(id)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }

    private fun enrichWithImages(service: ProvidedService): ProvidedServiceResponse {
        val images = imageService.findByServiceIds(listOfNotNull(service.id))
        return ProvidedServiceResponse.from(service, images)
    }

    private fun enrichWithImages(services: List<ProvidedService>): List<ProvidedServiceResponse> {
        val ids = services.mapNotNull { it.id }
        val imagesByServiceId = imageService.findByServiceIds(ids).groupBy { it.serviceId }
        return services.map { s -> ProvidedServiceResponse.from(s, imagesByServiceId[s.id] ?: emptyList()) }
    }
}
