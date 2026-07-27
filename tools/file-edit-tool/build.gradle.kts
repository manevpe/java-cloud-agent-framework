// File-edit tool (apply a patch/write a file into the sandbox workspace)
// — shareable `ToolBundle`, used by `coding-agent`.
dependencies {
    compileOnly(project(":core"))
    implementation(project(":tools:tool-support"))
    implementation("org.springframework.ai:spring-ai-model")

    testImplementation(project(":core"))
}
