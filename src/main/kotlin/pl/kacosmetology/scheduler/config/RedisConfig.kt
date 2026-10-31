package pl.kacosmetology.scheduler.config

import java.time.Duration
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableCaching
@EnableScheduling
class RedisConfig {

    /** Concurrent rotation/expiry may leave a delta-only hash. Fail closed instead of mapping it as a session. */
    @Bean
    fun safeSessionMapper(
        @org.springframework.beans.factory.annotation.Value("\${spring.session.data.redis.namespace:spring:session}")
        namespace: String,
    ): org.springframework.session.config.SessionRepositoryCustomizer<
        org.springframework.session.data.redis.RedisIndexedSessionRepository,
    > =
        org.springframework.session.config.SessionRepositoryCustomizer { repository ->
            val delegate = org.springframework.session.data.redis.RedisSessionMapper()
            repository.setRedisSessionMapper { id, values ->
                try {
                    delegate.apply(id, values)
                } catch (_: IllegalStateException) {
                    // Never use deleteById here: the indexed repository invokes this mapper during deletion.
                    repository.sessionRedisOperations.delete("$namespace:sessions:$id")
                    // Spring Session 4.1's mapper signature is non-null even though getSession accepts null.
                    // An already-expired, empty tombstone has the same fail-closed behavior without an unsafe cast.
                    org.springframework.session.MapSession(id).apply {
                        creationTime = java.time.Instant.EPOCH
                        lastAccessedTime = java.time.Instant.EPOCH
                        maxInactiveInterval = Duration.ZERO
                    }
                }
            }
        }

    @Bean
    fun cacheManager(connectionFactory: RedisConnectionFactory): RedisCacheManager {
        val jsonSerializer = RedisSerializer.json()

        val defaultConfig =
            RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(
                    RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer()),
                )
                .serializeValuesWith(
                    RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer),
                )
                .disableCachingNullValues()
                .entryTtl(Duration.ofMinutes(10))

        val cacheConfigs =
            mapOf(
                "companyServices" to defaultConfig.entryTtl(Duration.ofMinutes(15)),
            )

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigs)
            .build()
    }
}
