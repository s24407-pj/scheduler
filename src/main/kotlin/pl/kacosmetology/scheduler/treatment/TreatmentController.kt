package pl.kacosmetology.scheduler.treatment

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import pl.kacosmetology.scheduler.treatment.dto.TreatmentRequest
import pl.kacosmetology.scheduler.security.CustomUserDetails

/** REST API for managing salon services (treatments). */
@RestController
@RequestMapping("/api/services")
class TreatmentController(
    private val treatmentService: TreatmentService
) {

    /** Returns all services for a given company. */
    @GetMapping("/company/{companyId}")
    fun getCompanyServices(
        @PathVariable companyId: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): List<ProvidedService> {
        if (userDetails.companyId != companyId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak dostępu do tej firmy")
        }
        return treatmentService.getCompanyServices(companyId)
    }

    /** Returns a single service by ID. */
    @GetMapping("/{id}")
    fun getServiceById(
        @PathVariable id: Long,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ProvidedService {
        val service = findServiceOrThrow(id)
        if (service.companyId != userDetails.companyId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak dostępu do tej usługi")
        }
        return service
    }

    /** Creates a new service for the authenticated user's company. Requires OWNER role. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER')")
    fun createService(
        @Valid @RequestBody request: TreatmentRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ProvidedService {
        return treatmentService.createService(requireCompanyId(userDetails), request)
    }

    /** Updates an existing service. Requires OWNER role. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER')")
    fun updateService(
        @PathVariable id: Long,
        @Valid @RequestBody request: TreatmentRequest,
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ProvidedService {
        return try {
            treatmentService.updateService(id, requireCompanyId(userDetails), request)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message)
        }
    }

    /** Deletes a service. Requires OWNER role. */
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

    private fun requireCompanyId(userDetails: CustomUserDetails): Long {
        return userDetails.companyId
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisania do firmy")
    }

    private fun findServiceOrThrow(id: Long): ProvidedService {
        return try {
            treatmentService.getServiceById(id)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }
    }
}