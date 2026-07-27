// Conversational planning agent — same as `planning-agent` but pauses to
// ask clarifying questions in Slack (via `ask-human-tool`) whenever the
// plan is uncertain, resuming once a developer answers.
dependencies {
    compileOnly(project(":core"))
    compileOnly("org.slf4j:slf4j-api")
    compileOnly("org.springframework:spring-core")
    compileOnly("org.springframework.ai:spring-ai-model")
    implementation(project(":tools:file-read-tool"))
    implementation(project(":tools:workspace-setup-tool"))
    implementation(project(":tools:ask-human-tool"))

    testImplementation(project(":core"))
}
