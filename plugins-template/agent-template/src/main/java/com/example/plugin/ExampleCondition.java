package com.example.plugin;

import io.github.manevpe.agentic.engine.EdgeCondition;
import io.github.manevpe.agentic.workflow.WorkflowState;

/**
 * Template for a custom edge-routing predicate, loaded from an external
 * jar via {@code ServiceLoader}. Copy this class (rename freely), then:
 *
 * <ol>
 *   <li>Implement {@link #test} to inspect {@code state} and return
 *       whether this outgoing edge should be taken.</li>
 *   <li>List this class under {@code
 *       src/main/resources/META-INF/services/io.github.manevpe.agentic.engine.EdgeCondition}
 *       so {@code ServiceLoader} can find it.</li>
 * </ol>
 *
 * <p>Unlike built-in conditions (registered as named Spring beans),
 * plugin-provided conditions have no bean name to borrow — they are
 * registered under their implementing class's simple name, decapitalized.
 * This class would be referenced from workflow YAML as {@code
 * condition: exampleCondition}.
 *
 * <p>Must be {@code public} with a {@code public} no-arg constructor, same
 * as {@link ExampleAgent} — see that class's Javadoc for why.
 */
public class ExampleCondition implements EdgeCondition {

    @Override
    public boolean test(WorkflowState state) {
        return state.get("exampleAgentRan", Boolean.class).orElse(false);
    }
}
