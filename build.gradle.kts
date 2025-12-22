// Plugins to extend Gradle functionality
plugins {
    id("io.micronaut.application") version "4.6.1"
    id("io.micronaut.aot") version "4.6.1"
    id("com.google.cloud.tools.jib") version "3.5.2"
    id("checkstyle")
    id("com.diffplug.spotless") version "8.1.0"
}

// Set dependency source and declare required dependencies
// Classification of dependencies influences whether they are included in the final build artifact
repositories {
    mavenCentral()
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-websocket")
    compileOnly("io.micronaut:micronaut-http-client")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")
    testImplementation("io.micronaut:micronaut-http-client")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Compile Java source code with specified compatibility version
java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}

// Set application artifact details and configure application entry point
version = "0.1"
group = "zugzwang-realtime-messaging"

application {
    mainClass = "messaging.Application"
}

// Micronaut framework specific configurations
micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("messaging.*") // Hint on where to look - adjust this as needed to match package structure
    }
    aot {
        // Please review carefully the optimizations enabled below
        // Check https://micronaut-projects.github.io/micronaut-aot/latest/guide/ for more details
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

// Docker image generation
jib {
    from {
        image = "gcr.io/distroless/java21-debian12"
    }
    to {
        image = "realtime-messaging"
        tags = setOf(project.version.toString())
    }
    container {
        ports = listOf("8080")
        creationTime = "USE_CURRENT_TIMESTAMP"
        jvmFlags = listOf("-XX:MaxRAMPercentage=75.0")
    }
    containerizingMode = "exploded"
}

// Linting
checkstyle {
    toolVersion = "12.3.0"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}

spotless {
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        googleJavaFormat("1.17.0")
    }
}

// Custom scripts to simplify common tasks
tasks.register<Exec>("dockerBuildPrune") {
    dependsOn(tasks.named("jibDockerBuild"))
    commandLine("docker", "image", "prune", "-f")
}

tasks.register<Exec>("dockerComposeUp") {
    commandLine("docker", "compose", "up", "-d")
}

tasks.register<Exec>("dockerComposeDown") {
    commandLine("docker", "compose", "down")
}
