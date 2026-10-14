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

    /** API info and the session cookie scheme, so Swagger UI can send authenticated requests. */
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(Info().title("Scheduler API").version("v1"))
            .components(
                Components()
                    .addSecuritySchemes(
                        SESSION_AUTH,
                        SecurityScheme()
                            .type(SecurityScheme.Type.APIKEY)
                            .`in`(SecurityScheme.In.COOKIE)
                            .name("SESSION"),
                    ),
            )
            .addSecurityItem(SecurityRequirement().addList(SESSION_AUTH))

    private companion object {
        const val SESSION_AUTH = "sessionAuth"
    }
}
