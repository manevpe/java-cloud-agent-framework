// Full end-to-end integration tests for the out-of-the-box workflow:
// runs `core` as a real `@SpringBootTest` app, with every agent/tool
// module below loaded at runtime through the same `PluginManager`
// ServiceLoader mechanism (ADR-0010) production uses — never via Spring
// component-scanning. This module has no `src/main` of its own; it only
// exists to exercise the other modules together (see ADR-0014).
plugins {
    id("java")
    id("io.spring.dependency-management")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.0")
    }
}

// Every out-of-the-box tool/agent module, listed once so it can drive
// both the `dependencies {}` block below and the plugin-jar-aggregation
// task — avoids the two lists silently drifting apart as modules are
// added/removed.
val pluginModules = listOf(
    "tools:tool-support",
    "tools:workspace-setup-tool",
    "tools:file-read-tool",
    "tools:file-edit-tool",
    "tools:ask-human-tool",
    "tools:github-api-tool",
    "tools:http-request-tool",
    "agents:planning-agent",
    "agents:conversational-planning-agent",
    "agents:coding-agent",
    "agents:jira-updater-agent",
    "agents:slack-gate-agent",
    "agents:pr-comment-gate-agent",
    "agents:conversation-resume-gate-agent",
)

dependencies {
    testImplementation(project(":core"))
    pluginModules.forEach { testImplementation(project(":$it")) }

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4.1 modularized MockMvc test support out of spring-boot-test-autoconfigure.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:neo4j")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework.ai:spring-ai-model")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// `PluginManager` scans one flat directory for `*.jar` files (ADR-0010).
// Now that every out-of-the-box agent/tool is its own Gradle
// module/jar, the flow tests need all of them copied into a single
// directory rather than pointing at one module's own `build/libs`
// (which is all a single-jar `agents` module used to need). Each
// dependency here is the *sibling module's* `:jar` task, not this
// module's own (this module produces no main jar at all).
val aggregatePlugins by tasks.registering(Copy::class) {
    pluginModules.forEach { modulePath ->
        from(project(":$modulePath").tasks.named("jar"))
    }
    into(layout.buildDirectory.dir("plugins"))
}

tasks.test {
    dependsOn(aggregatePlugins)
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
    options.encoding = "UTF-8"
}
