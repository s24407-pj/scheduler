package pl.kacosmetology.scheduler.user.dto

import jakarta.validation.constraints.Size

/** Request DTO for setting free-text notes on a customer. */
data class UpdateCustomerNotesRequest(
    @field:Size(max = 2000, message = "Notatka może mieć najwyżej 2000 znaków") val notes: String?,
)
