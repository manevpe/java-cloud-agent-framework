package io.github.manevpe.agentic.agent.conversation;

import io.github.manevpe.agentic.agent.Agent;
import io.github.manevpe.agentic.agent.AgentResult;
import io.github.manevpe.agentic.workflow.NodeDefinition;
import io.github.manevpe.agentic.workflow.WorkflowState;

/**
 * The actual pause point for any conversation-session-based agent (e.g.
 * {@code ConversationalPlanningAgent}) — a trivial, reusable node that
 * always pauses on whatever correlation key the owning agent left in
 * state, then loops back into that same agent once the human's reply
 * resumes it. Registered under {@code conversation-resume-gate} in
 * workflow YAML.
 *
 * <p>This mirrors the split already used by {@code human-gate}/{@code
 * pr-comment-gate}: per the LangGraph4j constraint documented on {@code
 * WorkflowGraphFactory}, a node can't both be an {@code interruptsAfter}
 * point and conditionally continue, so the "does the real work" agent
 * (which decides whether to pause) and "is the actual pause point" node
 * must be separate. Unlike those two, this single gate node is reused
 * across every conversation-session-based agent, since its job is
 * identical regardless of which agent is driving the conversation: read
 * {@code humanQuestionCorrelationKey} from state and wait.
 */
public class ConversationResumeGateAgent implements Agent {

    @Override
    public String type() {
        return "conversation-resume-gate";
    }

    @Override
    public AgentResult execute(NodeDefinition node, WorkflowState state) {
        String correlationKey = state.get("humanQuestionCorrelationKey", String.class)
                .orElseThrow(() -> new IllegalStateException(
                        "conversation-resume-gate reached without a humanQuestionCorrelationKey in state"));
        return new AgentResult.WaitForEvent(state, correlationKey);
    }
}
