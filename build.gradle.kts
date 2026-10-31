import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

// Wersje bibliotek i pluginów są definiowane w gradle/libs.versions.toml
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spotless)
    alias(libs.plugins.kover)
}

group = "pl.kacosmetology"

version = "0.0.1-SNAPSHOT"

description = "scheduler"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    // Database
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // ImageIO decoder verifies actual WebP payloads as well as standard JPEG/PNG.
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.15.3")

    // AWS SDK (for Cloudflare R2 — S3-compatible)
    implementation(libs.aws.s3)

    // OpenAPI spec + Swagger UI (enabled only in the dev profile)
    implementation(libs.springdoc.openapi.webmvc.ui)

    // Redis-backed servlet sessions (Spring Boot managed)
    implementation("org.springframework.boot:spring-boot-starter-session-data-redis")

    // Test - Spring Boot
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")

    // Test - Kotlin
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")

    // Test - Testcontainers
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.redis)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Test - Mocking
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)

    // Test - Architecture
    testImplementation(libs.archunit)
}

spotless {
    kotlin {
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle().configure { it.setMaxWidth(120) }
    }
    kotlinGradle {
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle().configure { it.setMaxWidth(120) }
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("user.timezone", "UTC")
    systemProperty("totp.encryption-key", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=")
    systemProperty("scheduler.jobs.enabled", "false")
    systemProperty("spring.profiles.active", "test")
}

tasks.named<Jar>("jar") {
    enabled = false
}

val moduleCoverageBranchMinimums =
    mapOf("auth" to 70, "reservation" to 70, "security" to 70, "user" to 60, "company" to 60)

kover {
    // Filtered variants share JVM test data while verifying each module independently.
    currentProject {
        moduleCoverageBranchMinimums.keys.forEach { module ->
            createVariant(module) {
                add("jvm")
            }
        }
    }
    reports {
        total {
            html {
                htmlDir = layout.buildDirectory.dir("reports/kover/html")
            }
            xml {
                xmlFile = layout.buildDirectory.file("reports/kover/report.xml")
            }
            verify {
                onCheck = false
                rule("Whole-backend coverage") {
                    minBound(80, CoverageUnit.LINE)
                    minBound(60, CoverageUnit.BRANCH)
                }
            }
        }
        moduleCoverageBranchMinimums.forEach { (module, branchMinimum) ->
            variant(module) {
                filters {
                    includes {
                        packages("pl.kacosmetology.scheduler.$module")
                    }
                }
                verify {
                    onCheck = false
                    rule("$module coverage") {
                        minBound(90, CoverageUnit.LINE)
                        minBound(branchMinimum, CoverageUnit.BRANCH)
                    }
                }
            }
        }
    }
}

val moduleCoverageVerificationTaskNames =
    moduleCoverageBranchMinimums.keys.map { "koverVerify${it.replaceFirstChar(Char::uppercaseChar)}" }

// Keep reports available when verification fails, without adding a gate to ordinary local builds.
tasks.named("koverVerify") {
    dependsOn("koverHtmlReport", "koverXmlReport")
    dependsOn(moduleCoverageVerificationTaskNames)
}

tasks
    .matching { it.name in moduleCoverageVerificationTaskNames }
    .configureEach {
        dependsOn("koverHtmlReport", "koverXmlReport")
    }
