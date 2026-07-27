// Minimal template build for a framework plugin jar. Point `frameworkJar`
// at a built `java-cloud-agent-framework-*.jar` (from `./gradlew jar` in the
// main repo) so the compiler can see the Agent/EdgeCondition/Skill API
// packages. No Spring Boot plugin, no fat-jar shading needed — this
// produces a plain jar that the framework loads at runtime via its own
// classpath, not yours.
plugins {
    id("java")
}

group = "com.example"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

val frameworkJar: String = findProperty("frameworkJar") as String?
    ?: "../../build/libs/java-cloud-agent-framework-0.1.0-SNAPSHOT-plain.jar"

dependencies {
    compileOnly(files(frameworkJar))
    // Skill.tools() bundles a Spring AI ToolCallback — spring-ai-commons +
    // spring-ai-model provide the @Tool annotation and ToolCallback/
    // ToolCallbacks types used by ExampleTool/ExampleSkill.
    compileOnly("org.springframework.ai:spring-ai-commons:1.0.0")
    compileOnly("org.springframework.ai:spring-ai-model:1.0.0")
}
