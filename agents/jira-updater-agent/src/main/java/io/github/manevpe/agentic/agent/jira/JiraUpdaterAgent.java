package io.github.manevpe.agentic.agent.jira;

import io.github.manevpe.agentic.agent.Agent;
import io.github.manevpe.agentic.agent.AgentResult;
import io.github.manevpe.agentic.integration.JiraClient;
import io.github.manevpe.agentic.plugin.PluginContext;
import io.github.manevpe.agentic.plugin.PluginContextAware;
import io.github.manevpe.agentic.workflow.NodeDefinition;
import io.github.manevpe.agentic.workflow.WorkflowState;

/**
 * Posts the finalized plan back to the originating Jira ticket. Registered
 * under {@code jira-updater-agent} in workflow YAML.
 *
 * <p>Posting a plan comment is low-risk and reversible, so it executes
 * immediately rather than pausing for approval (see ADR-0004). The plan
 * text is already final by the time this runs — {@code PlanningAgent}
 * incorporates every clarification round directly into {@code plan}/
 * {@code finalPlan} as it redrafts (see its Javadoc), so this agent no
 * longer needs to concatenate a raw Slack answer onto it itself.
 */
public class JiraUpdaterAgent implements Agent, PluginContextAware {

    private JiraClient jiraClient;

    public JiraUpdaterAgent() {
    }

    @Override
    public void setPluginContext(PluginContext context) {
        this.jiraClient = context.jiraClient();
    }

    @Override
    public String type() {
        return "jira-updater-agent";
    }

    @Override
    public AgentResult execute(NodeDefinition node, WorkflowState state) {
        String ticketKey = state.get("ticketKey", String.class).orElse("UNKNOWN");
        String finalPlan = state.get("finalPlan", String.class)
                .or(() -> state.get("plan", String.class))
                .orElse("");

        jiraClient.postComment(ticketKey, finalPlan);
        state.put("finalPlan", finalPlan);
        return new AgentResult.Continue(state);
    }
}
