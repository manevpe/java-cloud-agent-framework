// File-read tool (read a file's contents from the sandbox workspace) —
// shareable `ToolBundle`, used by `planning-agent` and
// `conversational-planning-agent`.
dependencies {
    compileOnly(project(":core"))
    implementation(project(":tools:tool-support"))
    implementation("org.springframework.ai:spring-ai-model")

    testImplementation(project(":core"))
}
