package pl.kacosmetology.scheduler.company.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Owner-controlled public company presentation, scoped by the authenticated staff account. */
data class UpdateCompanyProfileRequest(
    @field:NotBlank(message = "Nazwa firmy jest wymagana")
    @field:Size(max = 255, message = "Nazwa firmy nie może przekraczać 255 znaków")
    val name: String,
    @field:Size(max = 255, message = "Adres nie może przekraczać 255 znaków") val address: String? = null,
)
