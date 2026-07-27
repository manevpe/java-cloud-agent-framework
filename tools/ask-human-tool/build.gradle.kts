// "Ask a human" tool (opens/polls a Slack-backed clarification thread) —
// shareable `ToolBundle`, used by `conversational-planning-agent`. Does
// not use `ToolResults` (its result strings can never legitimately be
// blank), so no dependency on `tools:tool-support`.
dependencies {
    compileOnly(project(":core"))
    implementation("org.springframework.ai:spring-ai-model")

    testImplementation(project(":core"))
}
