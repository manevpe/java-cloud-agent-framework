// Generic HTTP-fetch tool (arbitrary URL GET) — shareable `ToolBundle`.
// Standalone: unlike GitHubClient/JiraClient/SlackClient it has no
// credentials or swappable backend, so it needs neither PluginContext
// wiring nor a Logging stub — it's the same real implementation in every
// environment, just capped in what it will fetch/return (see
// HttpRequestTool's Javadoc).
dependencies {
    compileOnly(project(":core"))
    implementation(project(":tools:tool-support"))
    implementation("org.springframework.ai:spring-ai-model")
    implementation("org.springframework:spring-web")

    testImplementation(project(":core"))
}
