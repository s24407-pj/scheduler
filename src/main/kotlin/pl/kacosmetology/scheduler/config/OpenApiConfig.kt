package pl.kacosmetology.scheduler.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import java.time.LocalDateTime
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.kacosmetology.scheduler.security.ExpectedSessionContextFilter

/** OpenAPI metadata for springdoc. The spec and Swagger UI are enabled only in the `dev` profile. */
@Configuration
class OpenApiConfig {
    init {
        SpringDocUtils.getConfig()
            .replaceWithSchema(
                LocalDateTime::class.java,
                StringSchema()
                    .format("local-date-time")
                    .pattern("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,6})?)?$")
                    .description(
                        "Europe/Warsaw local date-time without Z or offset; unique DST endpoints, at most microsecond precision",
                    ),
            )
    }

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
            .also { api ->
                api.components.addSecuritySchemes(
                    "expectedSessionContext",
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .`in`(SecurityScheme.In.HEADER)
                        .name(ExpectedSessionContextFilter.HEADER)
                        .description(
                            "Required equality precondition for protected writes, not an authentication credential: " +
                                "v1:userId:companyId:employmentId:ROLE_OWNER (or ROLE_EMPLOYEE), captured from auth/me " +
                                "when opening the form; customers use v1:userId:companyId:null:ROLE_CUSTOMER. Missing/malformed: 400 SESSION_CONTEXT_REQUIRED; " +
                                "changed cookie scope: 409 SESSION_CONTEXT_CHANGED. Not required for customer logout-all.",
                        ),
                )
                api.components.addSecuritySchemes(
                    "csrfToken",
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .`in`(SecurityScheme.In.HEADER)
                        .name("X-XSRF-TOKEN")
                        .description(
                            "Fetch /api/auth/csrf before unsafe calls; send the token with its cookie. Refresh after login/logout.",
                        ),
                )
            }

    /** Makes public operations explicit and documents CSRF independently of authentication. */
    @Bean
    fun routeSecurity(): OpenApiCustomizer = OpenApiCustomizer { api ->
        api.components.addSchemas(
            "ApiProblemDetail",
            ObjectSchema()
                .addProperty("type", StringSchema())
                .addProperty("title", StringSchema())
                .addProperty("status", IntegerSchema())
                .addProperty("detail", StringSchema().description("Polish user-facing message"))
                .addProperty("instance", StringSchema())
                .addProperty("code", StringSchema().description("Stable application error code"))
                .addProperty(
                    "errors",
                    ObjectSchema().additionalProperties(StringSchema()).description("Polish field validation messages"),
                )
                .addProperty("existingCustomer", ObjectSchema().addProperty("id", StringSchema().format("uuid")))
                .required(listOf("status", "code")),
        )
        // springdoc otherwise emits circular union/subtype inheritance for the Jackson sealed selector.
        for ((name, kind, requiredFields) in
            listOf(
                Triple("ExistingCustomerSelection", "existing", listOf("kind", "customerId")),
                Triple("NewCustomerSelection", "new", listOf("kind", "phoneNumber", "firstName", "lastName")),
            )) {
            val generated = api.components.schemas[name] ?: continue
            val properties =
                generated.allOf?.flatMap { it.properties?.entries.orEmpty() }?.associate { it.key to it.value }
                    ?: generated.properties.orEmpty()
            api.components.addSchemas(
                name,
                ObjectSchema().apply {
                    properties.forEach { (key, value) -> addProperty(key, value) }
                    addProperty("kind", StringSchema()._enum(listOf(kind)))
                    required = requiredFields
                    additionalProperties = false
                },
            )
        }
        api.components.schemas["StaffCustomerSelection"]?.apply {
            properties = null
            required = null
            discriminator.mapping =
                mapOf(
                    "existing" to "#/components/schemas/ExistingCustomerSelection",
                    "new" to "#/components/schemas/NewCustomerSelection",
                )
        }
        api.paths?.forEach { (path, item) ->
            item.readOperationsMap().forEach { (method, operation) ->
                val public =
                    path.contains("/public/") ||
                        path == "/api/availability" ||
                        path in
                            setOf(
                                "/api/auth/csrf",
                                "/api/auth/logout",
                                "/api/auth/request-code",
                                "/api/auth/verify-code",
                                "/api/auth/login-staff",
                                "/api/auth/login-staff-email",
                                "/api/auth/complete-staff-login",
                                "/api/auth/staff-password",
                            )
                val unsafe =
                    method !in setOf(PathItem.HttpMethod.GET, PathItem.HttpMethod.HEAD, PathItem.HttpMethod.OPTIONS)
                val requirement = SecurityRequirement()
                if (!public) requirement.addList(SESSION_AUTH)
                if (unsafe) requirement.addList("csrfToken")
                val logoutAll = path == "/api/auth/logout-all" && method == PathItem.HttpMethod.POST
                if (!public && unsafe && !logoutAll) requirement.addList("expectedSessionContext")
                if (
                    logoutAll && operation.parameters?.none { it.name == ExpectedSessionContextFilter.HEADER } != false
                ) {
                    operation.addParametersItem(
                        HeaderParameter()
                            .name(ExpectedSessionContextFilter.HEADER)
                            .required(false)
                            .schema(StringSchema())
                            .description(
                                "Conditionally required for ROLE_OWNER and ROLE_EMPLOYEE sessions " +
                                    "(v1:userId:companyId:employmentId:ROLE_OWNER or ROLE_EMPLOYEE); " +
                                    "not required for ROLE_CUSTOMER.",
                            ),
                    )
                }
                operation.security = if (requirement.isEmpty()) emptyList() else listOf(requirement)
                listOf("400", "401", "403", "404", "409", "429").forEach { code ->
                    operation.responses.putIfAbsent(
                        code,
                        ApiResponse()
                            .description("Problem Details; inspect stable code and Polish detail/errors")
                            .content(
                                Content()
                                    .addMediaType(
                                        "application/problem+json",
                                        MediaType()
                                            .schema(Schema<Any>().`$ref`("#/components/schemas/ApiProblemDetail")),
                                    ),
                            ),
                    )
                }
            }
        }
    }

    private companion object {
        const val SESSION_AUTH = "sessionAuth"
    }
}
