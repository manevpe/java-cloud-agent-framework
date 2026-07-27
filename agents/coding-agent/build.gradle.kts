// Coding agent — reads the approved plan, implements it in an isolated
// sandbox workspace, and opens a PR; also handles PR-review-response
// (amending commits after reviewer comments), selected via its `mode`
// config (ADR: one CodingAgent class covers both node roles).
//
// Only `workspace-setup-tool` is a compile-time dependency (this class
// casts to the concrete `WorkspaceSetupTool` type via
// `ToolRegistry.resolveInstance` to reuse the same sandbox pod across
// calls). `file-read-tool` and `file-edit-tool` are resolved purely by
// name through `ToolRegistry.resolveTools(...)` at runtime — no compile
// dependency needed here, but both of their jars must still be present
// in the deployed plugins directory alongside this one for the agent to
// actually work.
dependencies {
    compileOnly(project(":core"))
    compileOnly("org.slf4j:slf4j-api")
    compileOnly("org.springframework:spring-core")
    implementation(project(":tools:workspace-setup-tool"))
    // For this agent's own private, non-shareable `@Tool` methods
    // (SubmitImplementationResultTool, SubmitPrResponseTool).
    implementation("org.springframework.ai:spring-ai-model")

    testImplementation(project(":core"))
}
