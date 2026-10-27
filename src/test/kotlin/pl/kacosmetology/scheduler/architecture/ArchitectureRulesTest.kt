package pl.kacosmetology.scheduler.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import jakarta.persistence.EntityManager
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import pl.kacosmetology.scheduler.fixturealpha.*
import pl.kacosmetology.scheduler.fixturebeta.*
import pl.kacosmetology.scheduler.security.CustomUserDetailsService
import pl.kacosmetology.scheduler.security.SessionAuthenticationService

class ArchitectureRulesTest {
    @TestFactory
    fun `foreign service dependencies should be rejected regardless of syntax or infrastructure package`() =
        listOf(
                WildcardClient::class.java to ForeignService::class.java,
                AliasClient::class.java to ForeignService::class.java,
                FullyQualifiedClient::class.java to ForeignService::class.java,
                UnexpectedSecurityClient::class.java to
                    pl.kacosmetology.scheduler.security.UnexpectedService::class.java,
                UnexpectedConfigClient::class.java to pl.kacosmetology.scheduler.config.UnexpectedService::class.java,
            )
            .map { (source, target) ->
                dynamicTest(source.simpleName) { assertViolation(ArchitectureRules.foreignServices, source, target) }
            }

    @TestFactory
    fun `controllers should reject repository and direct persistence access`() =
        listOf(
                ConstructorController::class.java to ForeignRepository::class.java,
                PropertyController::class.java to ForeignRepository::class.java,
                MethodController::class.java to ForeignRepository::class.java,
                JdbcController::class.java to JdbcTemplate::class.java,
                JdbcInterfaceController::class.java to JdbcOperations::class.java,
                NamedJdbcEndpoint::class.java to NamedParameterJdbcTemplate::class.java,
                NamedJdbcInterfaceController::class.java to NamedParameterJdbcOperations::class.java,
                JpaEndpoint::class.java to EntityManager::class.java,
                SpringRepositoryController::class.java to DataStore::class.java,
                AnnotatedRepositoryController::class.java to AnnotatedStore::class.java,
            )
            .map { (source, target) ->
                dynamicTest(source.simpleName) {
                    assertViolation(ArchitectureRules.controllerPersistence, source, target)
                }
            }

    @Test
    fun `local services shared infrastructure policies and cross-module repository cycles should remain permitted`() {
        val classes =
            ClassFileImporter()
                .importClasses(
                    AllowedClient::class.java,
                    LocalController::class.java,
                    LocalService::class.java,
                    SharedPolicy::class.java,
                    ForeignRepository::class.java,
                    AlphaRepository::class.java,
                    BetaRepository::class.java,
                    SessionAuthenticationService::class.java,
                    CustomUserDetailsService::class.java,
                )
        ArchitectureRules.foreignServices.check(classes)
        ArchitectureRules.controllerPersistence.check(classes)
        val alpha = classes.get(AlphaRepository::class.java)
        val beta = classes.get(BetaRepository::class.java)
        assertTrue(alpha.directDependenciesFromSelf.any { it.targetClass == beta })
        assertTrue(beta.directDependenciesFromSelf.any { it.targetClass == alpha })
    }

    private fun assertViolation(rule: ArchRule, source: Class<*>, target: Class<*>) {
        val classes = ClassFileImporter().importClasses(source)
        val result = rule.evaluate(classes)
        assertTrue(result.hasViolation(), "Expected ${source.name} to violate ${rule.description}")
        val details = result.failureReport.details
        assertFalse(details.isEmpty())
        assertTrue(details.any { source.name in it && target.name in it }, details.joinToString("\n"))
    }
}
