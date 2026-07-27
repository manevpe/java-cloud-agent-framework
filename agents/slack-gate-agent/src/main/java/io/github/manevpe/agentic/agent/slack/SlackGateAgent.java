package io.github.manevpe.agentic.agent.slack;

import io.github.manevpe.agentic.agent.Agent;
import io.github.manevpe.agentic.agent.AgentResult;
import io.github.manevpe.agentic.integration.SlackClient;
import io.github.manevpe.agentic.plugin.PluginContext;
import io.github.manevpe.agentic.plugin.PluginContextAware;
import io.github.manevpe.agentic.workflow.NodeDefinition;
import io.github.manevpe.agentic.workflow.WorkflowState;

import java.util.List;

/**
 * Starts a new Slack thread asking the team to clarify a plan's open
 * questions — a "grill-me"-style human gate — and pauses the workflow
 * until someone replies. Registered under {@code human-gate} in workflow
 * YAML.
 *
 * <p>This node is only ever routed to when the plan has open questions
 * (see the {@code hasOpenQuestions} edge condition), so it unconditionally
 * pauses; it never needs to decide whether to wait.
 */
public class SlackGateAgent implements Agent, PluginContextAware {

    private static final String DEFAULT_CHANNEL = "#dev-agent-plans";

    private SlackClient slackClient;

    public SlackGateAgent() {
    }

    @Override
    public void setPluginContext(PluginContext context) {
        this.slackClient = context.slackClient();
    }

    @Override
    public String type() {
        return "human-gate";
    }

    @Override
    public AgentResult execute(NodeDefinition node, WorkflowState state) {
        String channel = (String) node.config().getOrDefault("channel", DEFAULT_CHANNEL);
        String ticketKey = state.get("ticketKey", String.class).orElse("UNKNOWN");
        @SuppressWarnings("unchecked")
        List<String> openQuestions = state.get("openQuestions", List.class).orElse(List.of());

        String message = "Plan for %s needs clarification:\n- %s".formatted(
                ticketKey, String.join("\n- ", openQuestions));
        String threadId = slackClient.postThread(channel, message);

        state.put("slackThreadId", threadId);
        return new AgentResult.WaitForEvent(state, threadId);
    }
}
