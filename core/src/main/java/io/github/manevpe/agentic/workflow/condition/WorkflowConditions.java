package io.github.manevpe.agentic.workflow.condition;

import io.github.manevpe.agentic.engine.EdgeCondition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@link EdgeCondition} beans referenced by name from workflow YAML
 * edges (e.g. {@code condition: hasOpenQuestions}). Bean names must match
 * exactly, since {@code ConditionRegistry} resolves conditions by Spring
 * bean name — see {@code io.github.manevpe.agentic.engine.ConditionRegistry}.
 */
@Configuration
public class WorkflowConditions {

    @Bean
    public EdgeCondition hasOpenQuestions() {
        return state -> state.get("hasOpenQuestions", Boolean.class).orElse(false);
    }

    @Bean
    public EdgeCondition noOpenQuestions() {
        return state -> !state.get("hasOpenQuestions", Boolean.class).orElse(false);
    }

    /**
     * Gates {@code implement -> pr-feedback-loop}: true only once
     * {@code CodingAgent}'s in-process sandbox tool-calling loop has
     * finished implementing the change and merged {@code testsPassed: true}
     * into state before returning. If the sandbox reports failing tests (or
     * the edge simply doesn't match), routing falls through to {@code END}
     * with no explicit reject edge needed — see {@code WorkflowRoutingFunction}.
     */
    @Bean
    public EdgeCondition sandboxTestsPassed() {
        return state -> state.get("testsPassed", Boolean.class).orElse(false);
    }

    /**
     * Gates routing a conversation-session-based agent (e.g. {@code
     * ConversationalPlanningAgent}) into {@code conversation-resume-gate}:
     * true whenever that agent's last execution set {@code
     * conversationStatus: AWAITING_HUMAN} in state instead of finishing.
     */
    @Bean
    public EdgeCondition conversationAwaitingHuman() {
        return state -> state.get("conversationStatus", String.class)
                .map("AWAITING_HUMAN"::equals).orElse(false);
    }

    /**
     * Gates routing a conversation-session-based agent past its
     * conversation loop once it has produced a final result — true once
     * {@code conversationStatus: COMPLETED} is set in state.
     */
    @Bean
    public EdgeCondition conversationComplete() {
        return state -> state.get("conversationStatus", String.class)
                .map("COMPLETED"::equals).orElse(false);
    }

    /**
     * Gates looping back into {@code pr-comment-gate} after {@code
     * respond-to-pr-comment} (regardless of whether an amendment was
     * pushed inline this cycle — see {@code CodingAgent}'s
     * {@code respond-to-review} mode): true while the PR hasn't been
     * merged/closed and the configured {@code maxIterations} hasn't been
     * reached yet. If this doesn't match, routing falls through to
     * {@code END} — see {@code WorkflowRoutingFunction}.
     */
    @Bean
    public EdgeCondition canContinuePrFeedbackLoop() {
        return state -> {
            boolean closed = state.get("prThreadClosed", Boolean.class).orElse(false);
            int iteration = state.get("prFeedbackIteration", Integer.class).orElse(0);
            int maxIterations = state.get("prFeedbackMaxIterations", Integer.class).orElse(5);
            return !closed && iteration < maxIterations;
        };
    }
}
