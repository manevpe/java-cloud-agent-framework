// GitHub-API discovery tool (list an org's repositories, search code
// across GitHub) — shareable `ToolBundle`, used by `coding-agent` and any
// other agent that needs to figure out which repository a task belongs
// to *before* paying the cost of a full `gitClone` into a sandbox pod.
dependencies {
    compileOnly(project(":core"))
    implementation(project(":tools:tool-support"))
    implementation("org.springframework.ai:spring-ai-model")

    testImplementation(project(":core"))
}
