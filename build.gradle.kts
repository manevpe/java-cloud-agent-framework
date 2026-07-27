// Root build file: declares plugin versions once (each module `apply`s
// what it needs without repeating a version) and shares group/version/
// repositories across every module via `allprojects`. Module-specific
// dependencies and plugin application live in each module's own
// build.gradle.kts — see core/build.gradle.kts and agents/build.gradle.kts.
plugins {
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "io.github.manevpe.agentic"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

// Shared boilerplate for every out-of-the-box tool/agent module (one
// Gradle module per tool/agent — see ADR-0014). Each such module is a
// plain Java library (never the Spring Boot plugin: they're loaded at
// runtime as plugin jars via ServiceLoader, ADR-0010, not run
// standalone), but they still need: the same toolchain, the
// `-parameters` javac flag (Spring AI's `@Tool`/`@ToolParam` reflection
// needs parameter names — `core` gets this for free from the Spring Boot
// Gradle plugin, these modules don't), a BOM for consistent dependency
// versions, and a common JUnit 5 test setup. Declaring this once here
// avoids repeating it in ~12 module-specific build files; each module's
// own build.gradle.kts still declares its *own* dependencies (which
// tools/tool-support/core it actually needs).
configure(subprojects.filter { it.path.startsWith(":tools:") || it.path.startsWith(":agents:") }) {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            // Not applying the Spring Boot Gradle plugin here (these are
            // plain libraries, not runnable apps), but importing its BOM
            // via dependency-management alone keeps versions (JUnit,
            // AssertJ, etc.) aligned with `core` without repeating them.
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
            mavenBom("org.springframework.ai:spring-ai-bom:2.0.0")
        }
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
