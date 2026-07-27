rootProject.name = "java-cloud-agent-framework"

// "core" is the deployable Spring Boot application (the framework
// itself, with zero built-in agents) — see ADR-0011. Every out-of-the-box
// agent and shareable tool is its own Gradle module (one jar each), so
// downstream teams can deploy/version them independently and depend on
// only the tools they actually need (see ADR-0014). All of them are
// loaded by core at runtime via the ServiceLoader plugin mechanism
// (ADR-0010), exactly like any third-party plugin jar — none has special
// standing over a jar a downstream team writes themselves.
include(
    "core",

    // Shareable tools (ToolBundle implementations) — depended on by
    // whichever agent module(s) actually need them.
    "tools:tool-support",
    "tools:workspace-setup-tool",
    "tools:file-read-tool",
    "tools:file-edit-tool",
    "tools:ask-human-tool",
    "tools:github-api-tool",
    "tools:http-request-tool",

    // Out-of-the-box agents (Agent implementations) — one jar each.
    "agents:planning-agent",
    "agents:conversational-planning-agent",
    "agents:coding-agent",
    "agents:jira-updater-agent",
    "agents:slack-gate-agent",
    "agents:pr-comment-gate-agent",
    "agents:conversation-resume-gate-agent",

    // Full end-to-end flow tests exercising every agent+tool jar above
    // together, via the same ServiceLoader plugin-loading mechanism used
    // in production.
    "agents-integration-tests",
)
