// Workspace lifecycle tool (create/list/remove sandbox pod workspaces) —
// shareable `ToolBundle`, used by `planning-agent`, `conversational-planning-agent`,
// and `coding-agent`.
dependencies {
    compileOnly(project(":core"))
    compileOnly("org.slf4j:slf4j-api")
    implementation(project(":tools:tool-support"))
    implementation("org.springframework.ai:spring-ai-model")

    testImplementation(project(":core"))
}
