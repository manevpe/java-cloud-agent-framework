package io.github.manevpe.agentic.workflow;

import java.util.Objects;

/**
 * Describes what starts a workflow, or resumes it at a specific node, e.g.
 * a Jira webhook with a label condition, a Slack thread reply, or a GitHub
 * PR review comment event.
 */
public record TriggerDefinition(
        String type,
        String source,
        String condition,
        String event
) {

    public TriggerDefinition {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
    }
}
