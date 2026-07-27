// No tool dependencies — this agent only integrates with a deterministic
// REST client port from `core` (JiraClient/SlackClient) or is a pure
// gate/routing node.
dependencies {
    compileOnly(project(":core"))

    testImplementation(project(":core"))
}
