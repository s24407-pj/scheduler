package pl.kacosmetology.scheduler

import com.redis.testcontainers.RedisContainer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import pl.kacosmetology.scheduler.security.ExpectedSessionContextFilter
import pl.kacosmetology.scheduler.security.FixtureSessionCookie

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    /** Feature fixtures send their captured context like a client; real login tests set headers explicitly. */
    @Bean
    fun fixtureSessionContext(): MockMvcBuilderCustomizer = MockMvcBuilderCustomizer { builder ->
        builder.defaultRequest(
            get("/").with { request ->
                val cookie = request.cookies?.filterIsInstance<FixtureSessionCookie>()?.singleOrNull()
                if (
                    cookie != null &&
                        cookie.principal.companyId != null &&
                        request.getHeader(ExpectedSessionContextFilter.HEADER) == null
                ) {
                    request.addHeader(
                        ExpectedSessionContextFilter.HEADER,
                        ExpectedSessionContextFilter.context(cookie.principal),
                    )
                }
                request
            },
        )
    }

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer {
        return PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
    }

    @Bean
    @ServiceConnection
    fun redisContainer(): RedisContainer {
        return RedisContainer(DockerImageName.parse("redis:8-alpine"))
    }
}
