package pl.kacosmetology.scheduler.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** OpenAPI metadata for springdoc. The spec and Swagger UI are enabled only in the `dev` profile. */
@Configuration
class OpenApiConfig {

    /** API info and the JWT bearer scheme, so Swagger UI can send authenticated requests. */
    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(Info().title("Scheduler API").version("v1"))
        .components(
            Components().addSecuritySchemes(
                BEARER_AUTH,
                SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
            )
        )
        .addSecurityItem(SecurityRequirement().addList(BEARER_AUTH))

    private companion object {
        const val BEARER_AUTH = "bearerAuth"
    }
}
