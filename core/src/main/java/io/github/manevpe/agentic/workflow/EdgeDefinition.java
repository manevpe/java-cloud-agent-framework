package io.github.manevpe.agentic.workflow;

import java.util.Objects;

/**
 * A directed transition between two nodes, optionally gated by a named
 * condition. The condition name is resolved by the engine to a registered
 * {@code java.util.function.Predicate<WorkflowState>} bean — kept as a
 * string here so the core module has no dependency on how conditions are
 * implemented or wired.
 */
public record EdgeDefinition(String from, String to, String condition) {

    public EdgeDefinition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    public boolean isUnconditional() {
        return condition == null || condition.isBlank();
    }
}
