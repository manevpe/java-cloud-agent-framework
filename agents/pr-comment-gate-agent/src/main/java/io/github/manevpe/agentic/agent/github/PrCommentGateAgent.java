package io.github.manevpe.agentic.agent.github;

import io.github.manevpe.agentic.agent.Agent;
import io.github.manevpe.agentic.agent.AgentResult;
import io.github.manevpe.agentic.workflow.NodeDefinition;
import io.github.manevpe.agentic.workflow.WorkflowState;

/**
 * Pauses the workflow until the next relevant GitHub event on the PR
 * arrives — a submitted review (which may bundle several individual line
 * comments into one event), or the PR being merged/closed. Registered
 * under {@code pr-comment-gate} in workflow YAML.
 *
 * <p>Keying off GitHub's {@code pull_request_review} "submitted" event
 * (rather than the finer-grained {@code pull_request_review_comment}
 * event) is what lets {@code CodingAgent}'s {@code respond-to-review} mode
 * batch every comment from one review into a single amending commit
 * instead of pushing a separate commit per line comment — see {@code
 * CodingAgent}'s Javadoc.
 *
 * <p>The correlation key is derived deterministically from the ticket key
 * (not the PR URL, which typically contains slashes that would break
 * routing on {@code /webhooks/resume/{correlationKey}} — a single path
 * segment) so a single GitHub webhook target (configured once against the
 * repository, alongside whatever webhooks it already has — GitHub allows
 * multiple independent subscriptions to the same event type) keeps
 * resuming this thread across however many review/merge/close events
 * arrive, by routing them through an adapter that maps {@code prUrl ->
 * ticketKey -> correlationKey} and calls the generic {@code POST
 * /webhooks/resume/{correlationKey}} endpoint. The resumed payload's {@code
 * prEventType} field ({@code "review_submitted"}, {@code "merged"}, or
 * {@code "closed"}) tells {@code CodingAgent} which kind of event this
 * was.
 *
 * <p>{@code CodingAgent}'s {@code respond-to-review} mode loops back into
 * this same node id (a distinct id, never a graph self-loop — see {@code
 * WorkflowGraphFactory}'s Javadoc on why {@code interruptsAfter} can't
 * pause on a literal self-loop) once per review cycle, up to {@code
 * maxIterations}, or until the PR is merged/closed.
 */
public class PrCommentGateAgent implements Agent {

    @Override
    public String type() {
        return "pr-comment-gate";
    }

    @Override
    public AgentResult execute(NodeDefinition node, WorkflowState state) {
        String ticketKey = state.get("ticketKey", String.class).orElse("unknown-ticket");
        // Keyed off ticketKey rather than prUrl: prUrl contains slashes, which
        // break routing on /webhooks/resume/{correlationKey} (a single path
        // segment) — ticketKey is already unique per thread and slash-free.
        String correlationKey = "pr-comment:" + ticketKey;
        return new AgentResult.WaitForEvent(state, correlationKey);
    }
}
