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
            "src/test/java/**/*.java",
            "src/integrationTest/java/**/*.java"
        )
        googleJavaFormat("1.17.0")
    }
}

// ----------------------------
// Integration testing
// ----------------------------
val sourceSets = the<SourceSetContainer>()

val integrationTest: SourceSet by sourceSets.creating {
    java.srcDir("src/integrationTest/java")
    resources.srcDir("src/integrationTest/resources")

    // Compile integration tests against main + test compile deps
    compileClasspath += sourceSets["main"].output + configurations["testCompileClasspath"]

    // Run integration tests with everything above + test runtime deps
    runtimeClasspath += output + compileClasspath + configurations["testRuntimeClasspath"]
}

// Make integration test configurations inherit from unit test ones
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations["testImplementation"])
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations["testRuntimeOnly"])
configurations[integrationTest.annotationProcessorConfigurationName].extendsFrom(
    configurations["testAnnotationProcessor"],
    configurations["annotationProcessor"]
)

// Task to build the Envoy Docker image for integration tests
val buildEnvoyImage by tasks.registering(Exec::class) {
    group = "verification"
    description = "Builds the Envoy Docker image used for integration tests"

    workingDir = projectDir

    commandLine(
        "docker", "build",
        "-t", "realtime-envoy:it",
        "-f", "envoy/envoy.dockerfile",
        "envoy"
    )

    // Helpful logging
    doFirst {
        println("Building Envoy image realtime-envoy:it")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests."
    group = "verification"

    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath

    shouldRunAfter(tasks.test)
    useJUnitPlatform()

    testLogging {
        events("FAILED", "SKIPPED")
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
    }

    maxParallelForks = 1
    dependsOn(buildEnvoyImage)
    dependsOn(tasks.named("jibDockerBuild"))
}

tasks.named("check") {
    dependsOn("integrationTest")
    dependsOn("checkstyleIntegrationTest")
}

tasks.test {
    maxParallelForks = 1
}

// ----------------------------
// Dependencies
// ----------------------------
dependencies {
    // Annotation processors
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")

    // App
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut:micronaut-websocket")

    compileOnly("io.micronaut:micronaut-http-client")

    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")

    // Unit tests
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

    // Apply BOM to both unit tests and integration tests
    add("testImplementation", tcBom)
    add(integrationTest.implementationConfigurationName, tcBom)

    // Add Testcontainers modules to both
    add("testImplementation", "org.testcontainers:testcontainers-junit-jupiter")
    add("testImplementation", "org.testcontainers:testcontainers-postgresql")

    add(integrationTest.implementationConfigurationName, "org.testcontainers:testcontainers-junit-jupiter")
    add(integrationTest.implementationConfigurationName, "org.testcontainers:testcontainers-postgresql")

    // Integration-test-only helpers
    add(integrationTest.implementationConfigurationName, "com.squareup.okhttp3:okhttp:4.12.0")
    add(integrationTest.implementationConfigurationName, "com.fasterxml.jackson.core:jackson-databind:2.17.2")
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
