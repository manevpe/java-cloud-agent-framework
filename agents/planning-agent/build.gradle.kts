// Planning agent — reads Jira requirements + Neo4j domain knowledge,
// drafts an implementation plan, posts it back to Jira. Depends on the
// tools it actually resolves via `ToolRegistry`
// (`file-read-tool` for reading domain context files,
// `workspace-setup-tool` for the read-only sandbox workspace it sets up).
dependencies {
    compileOnly(project(":core"))
    compileOnly("org.slf4j:slf4j-api")
    compileOnly("org.springframework:spring-core")
    compileOnly("org.springframework.ai:spring-ai-model")
    implementation(project(":tools:file-read-tool"))
    implementation(project(":tools:workspace-setup-tool"))

    testImplementation(project(":core"))
}
