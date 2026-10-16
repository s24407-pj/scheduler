package pl.kacosmetology.scheduler.config

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.format.FormatterRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.module.SimpleModule

/** Parses an offset-free local date-time without silently discarding timezone data. */
fun parseLocalDateTime(value: String): LocalDateTime =
    LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).also {
        require(it.nano % 1000 == 0) { "Czas może zawierać maksymalnie 6 cyfr ułamkowych sekundy" }
    }

/** Strict JSON and query conversion; numeric fractions and unknown fields are never discarded. */
@Configuration
class StrictInputConfig : WebMvcConfigurer {
    /** Configures application-wide JSON input rules, including all integral DTO properties. */
    @Bean
    fun strictJson(): JsonMapperBuilderCustomizer = JsonMapperBuilderCustomizer { builder ->
        builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        builder.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        builder.addModule(SimpleModule().addDeserializer(LocalDateTime::class.java, StrictLocalDateTimeDeserializer()))
    }

    override fun addFormatters(registry: FormatterRegistry) {
        registry.addConverter(String::class.java, LocalDateTime::class.java, ::parseLocalDateTime)
    }
}

private class StrictLocalDateTimeDeserializer : StdDeserializer<LocalDateTime>(LocalDateTime::class.java) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): LocalDateTime =
        try {
            parseLocalDateTime(parser.string)
        } catch (_: RuntimeException) {
            context.handleWeirdStringValue(
                LocalDateTime::class.java,
                parser.valueAsString,
                "Wymagany czas lokalny bez strefy ani offsetu",
            ) as LocalDateTime
        }
}
