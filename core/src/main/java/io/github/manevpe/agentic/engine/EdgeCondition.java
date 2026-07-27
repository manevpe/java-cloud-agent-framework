package io.github.manevpe.agentic.engine;

import io.github.manevpe.agentic.workflow.WorkflowState;

/**
 * A named predicate over {@link WorkflowState}, registered as a Spring bean
 * under the name referenced by {@code EdgeDefinition.condition} in workflow
 * YAML (e.g. a bean named {@code hasOpenQuestions}). Keeping conditions as
 * named, independently-testable beans (rather than embedded expression
 * strings) keeps routing logic in ordinary Java, reusable across workflows.
 */
@FunctionalInterface
public interface EdgeCondition {
    boolean test(WorkflowState state);
}
