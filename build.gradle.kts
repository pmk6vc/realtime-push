import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.creating
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.the

plugins {
    id("io.micronaut.application") version "4.6.1"
    id("io.micronaut.aot") version "4.6.1"
    id("com.google.cloud.tools.jib") version "3.5.2"
    id("checkstyle")
    id("com.diffplug.spotless") version "8.1.0"
    java
}

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}

version = "0.1"
group = "zugzwang-realtime-messaging"

application {
    mainClass = "messaging.Application"
}

micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("messaging.*")
    }
    aot {
        optimizeServiceLoading = false
        convertYamlToJava = false
        precomputeOperations = true
        cacheEnvironment = true
        optimizeClassLoading = true
        deduceEnvironment = true
        optimizeNetty = true
        replaceLogbackXml = true
    }
}

// ----------------------------
// Linting / formatting
// ----------------------------
checkstyle {
    toolVersion = "12.3.0"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}

spotless {
    java {
        target(
            "src/main/java/**/*.java",
            "src/test/java/**/*.java"
        )
        googleJavaFormat("1.17.0")
    }
}

// ----------------------------
// Testing with JUnit tags
// ----------------------------
// All tests are in src/test/java
// E2E tests are marked with @Tag("e2e") and require Docker infrastructure

// Task to build the Envoy Docker image for E2E tests
val buildEnvoyImage by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the Envoy Docker image used for E2E tests"

    workingDir = projectDir

    commandLine(
        "docker", "build",
        "-t", "realtime-envoy:it",
        "-f", "envoy/envoy.dockerfile",
        "envoy"
    )

    doFirst {
        println("Building Envoy image realtime-envoy:it")
    }
}

// Default test task: runs unit and component tests (excludes @Tag("e2e"))
tasks.test {
    useJUnitPlatform {
        excludeTags("e2e")
    }

    testLogging {
        events("PASSED", "FAILED", "SKIPPED")
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
        showStandardStreams = false
    }

    maxParallelForks = 1

    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ descriptor, result ->
        if (descriptor.parent == null) {
            println("\nTest Summary: ${result.testCount} tests, ${result.successfulTestCount} passed, ${result.failedTestCount} failed, ${result.skippedTestCount} skipped")
        }
    }))
}

// E2E test task: runs only tests tagged with @Tag("e2e")
tasks.register<Test>("e2eTest") {
    description = "Runs E2E tests (requires Docker infrastructure)"
    group = "verification"

    useJUnitPlatform {
        includeTags("e2e")
    }

    shouldRunAfter(tasks.test)

    testLogging {
        events("PASSED", "FAILED", "SKIPPED")
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
        showStandardStreams = false
    }

    maxParallelForks = 1
    dependsOn(buildEnvoyImage)
    dependsOn(tasks.named("jibDockerBuild"))

    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ descriptor, result ->
        if (descriptor.parent == null) {
            println("\nE2E Test Summary: ${result.testCount} tests, ${result.successfulTestCount} passed, ${result.failedTestCount} failed, ${result.skippedTestCount} skipped")
        }
    }))
}

tasks.named("check") {
    dependsOn("test")
    dependsOn("e2eTest")
}

// ----------------------------
// Dependencies
// ----------------------------
dependencies {
    // ----------------------------
    // Annotation processors
    // ----------------------------
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    annotationProcessor("io.micronaut.data:micronaut-data-processor")

    // ----------------------------
    // Database
    // ----------------------------
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")
    implementation("io.micronaut.data:micronaut-data-jdbc")
    implementation("io.micronaut.flyway:micronaut-flyway")
    // Pin Flyway to a modern version that understands newer PG patch versions
    runtimeOnly("org.flywaydb:flyway-core:11.20.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:11.20.0")
    runtimeOnly("org.postgresql:postgresql")

    // ----------------------------
    // Application
    // ----------------------------
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut:micronaut-websocket")
    compileOnly("io.micronaut:micronaut-http-client")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")

    // ----------------------------
    // Unit tests
    // ----------------------------
    testImplementation("io.micronaut:micronaut-http-client")
    testImplementation("io.projectreactor:reactor-core")
    testImplementation("org.mockito:mockito-core:5.4.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.4.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // ----------------------------
    // Testcontainers 2.x (names changed in 2.x)
    // ----------------------------
    val tcBom = enforcedPlatform("org.testcontainers:testcontainers-bom:2.0.3")
    add("testImplementation", tcBom)
    add("testImplementation", "org.testcontainers:testcontainers-junit-jupiter")
    add("testImplementation", "org.testcontainers:testcontainers-postgresql")

    // E2E test helpers (used by tests with @Tag("e2e"))
    add("testImplementation", "com.squareup.okhttp3:okhttp:4.12.0")
    add("testImplementation", "com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

// ----------------------------
// Docker image generation
// ----------------------------
jib {
    from {
        image = "gcr.io/distroless/java21-debian12"
    }
    to {
        image = "realtime-messaging"
        tags = setOf(project.version.toString(), "it")
    }
    container {
        ports = listOf("8080")
        creationTime = "USE_CURRENT_TIMESTAMP"
        jvmFlags = listOf("-XX:MaxRAMPercentage=75.0")
    }
    containerizingMode = "exploded"
}

// ----------------------------
// Helper tasks
// ----------------------------
tasks.register<Exec>("dockerPrune") {
    dependsOn(tasks.named("jibDockerBuild"))
    commandLine("docker", "system", "prune", "--all", "-f")
}

tasks.register<Exec>("dockerComposeUp") {
    commandLine("docker", "compose", "up", "-d", "--force-recreate", "--scale", "messaging_app=3")
}

tasks.register<Exec>("dockerComposeDown") {
    commandLine("docker", "compose", "down")
}
