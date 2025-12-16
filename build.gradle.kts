// Plugins to extend Gradle functionality
plugins {
    id("io.micronaut.application") version "4.6.1"
    id("com.gradleup.shadow") version "8.3.9"
    id("io.micronaut.aot") version "4.6.1"
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
    compileOnly("io.micronaut:micronaut-http-client")
    runtimeOnly("ch.qos.logback:logback-classic")
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

// Image generation magic
graalvmNative.toolchainDetection = false

tasks.named<io.micronaut.gradle.docker.NativeImageDockerfile>("dockerfileNative") {
    jdkVersion = "21"
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
