package pl.kacosmetology.scheduler.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** CORS configuration. Allowed origins are read from the [cors.allowed-origins] environment variable. */
@Configuration
class CorsConfig(
    @Value($$"${cors.allowed-origins}") private val allowedOrigins: String,
) : WebMvcConfigurer {
    private val origins =
        allowedOrigins
            .split(",")
            .map { it.trim() }
            .also {
                require(it.isNotEmpty() && it.all { origin -> origin.isNotBlank() && '*' !in origin }) {
                    "Credentialed CORS requires explicit allowed origins"
                }
            }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry
            .addMapping("/**")
            .allowedOrigins(*origins.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
    }
}
