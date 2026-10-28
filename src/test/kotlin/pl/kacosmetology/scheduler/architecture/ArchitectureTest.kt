package pl.kacosmetology.scheduler.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ArchitectureTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages(ArchitectureRules.ROOT_PACKAGE)

    @Test
    fun `modules should not depend on services from other modules`() {
        ArchitectureRules.foreignServices.check(classes)
    }

    @Test
    fun `controllers should not access persistence directly`() {
        ArchitectureRules.controllerPersistence.check(classes)
    }

    @Test
    fun `production scan should include application classes but exclude test fixtures`() {
        assertTrue(classes.any { it.name == "pl.kacosmetology.scheduler.auth.AuthController" })
        assertTrue(classes.none { it.name.contains(".fixturealpha.") || it.name.contains(".fixturebeta.") })
        assertTrue(classes.none { it.name.endsWith("ArchitectureTest") || it.simpleName == "UnexpectedService" })
    }
}
