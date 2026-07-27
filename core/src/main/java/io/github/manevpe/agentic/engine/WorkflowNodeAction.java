package io.github.manevpe.agentic.engine;

import io.github.manevpe.agentic.agent.Agent;
import io.github.manevpe.agentic.agent.AgentResult;
import io.github.manevpe.agentic.integration.knowledge.NodeKnowledgeSourceResolver;
import io.github.manevpe.agentic.persistence.ApprovalStatus;
import io.github.manevpe.agentic.persistence.AuditLogEntry;
import io.github.manevpe.agentic.persistence.AuditLogRepository;
import io.github.manevpe.agentic.workflow.NodeDefinition;
import io.github.manevpe.agentic.workflow.WorkflowState;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.state.AgentState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Adapts a single workflow YAML node into a LangGraph4j node action: resolves
 * the node's {@code agent}, converts the graph's {@link AgentState} into our
 * storage-agnostic {@link WorkflowState}, invokes the agent, then translates
 * its {@link AgentResult} back into a state update plus engine bookkeeping
 * keys ({@link EngineStateKeys}) the routing edge (see
 * {@link WorkflowRoutingFunction}) uses to decide what happens next.
 *
 * <p>Every execution is appended to the audit log for traceability,
 * independent of whether the node succeeded, paused, or failed.
 *
 * <p>Also resolves this node's own {@code knowledgeSources: [...]} config
 * (see {@link NodeKnowledgeSourceResolver}) generically, before invoking
 * the agent, and writes the results into {@link
 * WorkflowState#KNOWLEDGE_CONTEXT} — this is a cross-cutting concern of
 * the engine, not something individual {@link Agent} implementations
 * should each re-resolve/re-inject themselves, so any agent (present or
 * future) picks it up simply by reading that state key.
 *
 * <p>{@code AgentResult} is a sealed interface, so the switch below is
 * intentionally exhaustive with no {@code default} branch: if a new result
 * type is ever added, every call site like this one fails to compile until
 * it explicitly decides how to handle it, rather than silently falling
 * through.
 */
class WorkflowNodeAction implements AsyncNodeActionWithConfig<AgentState> {

    private final NodeDefinition node;
    private final Agent agent;
    private final AuditLogRepository auditLogRepository;
    private final NodeKnowledgeSourceResolver knowledgeSourceResolver;

    WorkflowNodeAction(
            NodeDefinition node, Agent agent, AuditLogRepository auditLogRepository,
            NodeKnowledgeSourceResolver knowledgeSourceResolver) {
        this.node = node;
        this.agent = agent;
        this.auditLogRepository = auditLogRepository;
        this.knowledgeSourceResolver = knowledgeSourceResolver;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(AgentState state, RunnableConfig config) {
        String threadId = config.threadId().orElseThrow(
                () -> new IllegalStateException("RunnableConfig has no threadId"));

        WorkflowState inputState = new WorkflowState(businessKeysOnly(state.data()));
        inputState.put(WorkflowState.KNOWLEDGE_CONTEXT, resolveKnowledgeContext(inputState));
        AgentResult result = agent.execute(node, inputState);

        if (result instanceof AgentResult.Continue && isInterruptEligible(node)) {
            throw new IllegalStateException(
                    ("Node '%s' is registered as an interrupt point (requiresApproval or resumeTrigger) "
                            + "but its agent returned Continue. LangGraph4j will still pause execution here "
                            + "unconditionally, but with no WAITING status/correlation key recorded there is no "
                            + "way to resume it — the agent must return WaitForEvent/WaitForApproval instead.")
                            .formatted(node.id()));
        }
        if ((result instanceof AgentResult.WaitForEvent || result instanceof AgentResult.WaitForApproval)
                && !isInterruptEligible(node)) {
            throw new IllegalStateException(
                    ("Node '%s' returned WaitForEvent/WaitForApproval but is not registered as an interrupt "
                            + "point (needs requiresApproval: true or a resumeTrigger in its YAML definition). "
                            + "Without that, LangGraph4j will NOT actually pause here — it advances straight to "
                            + "the next node in the same invocation, silently ignoring the pause request.")
                            .formatted(node.id()));
        }

        Map<String, Object> update = new LinkedHashMap<>(result.state().asMap());
        update.put(EngineStateKeys.CURRENT_NODE_ID, node.id());

        switch (result) {
            case AgentResult.Continue c -> {
                update.put(EngineStateKeys.STATUS, NodeExecutionStatus.CONTINUE.name());
                audit(threadId, "NODE_COMPLETED", update, ApprovalStatus.NOT_REQUIRED);
            }
            case AgentResult.WaitForEvent w -> {
                update.put(EngineStateKeys.STATUS, NodeExecutionStatus.WAITING_FOR_EVENT.name());
                update.put(EngineStateKeys.WAITING_CORRELATION_KEY, w.correlationKey());
                audit(threadId, "NODE_WAITING_FOR_EVENT", update, ApprovalStatus.NOT_REQUIRED);
            }
            case AgentResult.WaitForApproval a -> {
                update.put(EngineStateKeys.STATUS, NodeExecutionStatus.WAITING_FOR_APPROVAL.name());
                update.put(EngineStateKeys.WAITING_CORRELATION_KEY, a.correlationKey());
                audit(threadId, "NODE_WAITING_FOR_APPROVAL", update, ApprovalStatus.PENDING);
            }
            case AgentResult.Failed f -> {
                update.put(EngineStateKeys.STATUS, NodeExecutionStatus.FAILED.name());
                update.put(EngineStateKeys.FAILURE_REASON, f.reason());
                audit(threadId, "NODE_FAILED", update, ApprovalStatus.NOT_REQUIRED);
            }
        }

        return CompletableFuture.completedFuture(update);
    }

    private void audit(String threadId, String actionType, Map<String, Object> payload, ApprovalStatus status) {
        auditLogRepository.append(
                AuditLogEntry.of(threadId, node.id(), node.agent(), actionType, businessKeysOnly(payload), status));
    }

    /**
     * Resolves this node's own {@code knowledgeSources: [...]} config (a
     * list of {@code {type, ...}} source specs, see {@link
     * NodeKnowledgeSourceResolver}) against a query text built generically
     * from every string-valued piece of business state accumulated so
     * far — e.g. {@code ticketKey}/{@code summary}/{@code description} on
     * the first node, {@code plan}/{@code reviewComments} once later nodes
     * have populated them — so no agent-specific knowledge of which state
     * keys matter is needed here either. An empty/missing list on this
     * node returns an empty list, by design; there is no implicit "query
     * everything" fallback.
     */
    private List<String> resolveKnowledgeContext(WorkflowState state) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceConfigs = (List<Map<String, Object>>) (List<?>) node.config()
                .getOrDefault("knowledgeSources", List.of());
        if (sourceConfigs.isEmpty()) {
            return List.of();
        }
        String queryText = queryTextFrom(state);
        return knowledgeSourceResolver.resolve(sourceConfigs, queryText);
    }

    private static String queryTextFrom(WorkflowState state) {
        return state.asMap().values().stream()
                .flatMap(WorkflowNodeAction::flattenToStrings)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static Stream<String> flattenToStrings(Object value) {
        if (value instanceof String s) {
            return Stream.of(s);
        }
        if (value instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast);
        }
        return Stream.empty();
    }

    /**
     * Nodes marked {@code requiresApproval} or with a {@code resumeTrigger} are
     * registered by {@link WorkflowGraphFactory} under LangGraph4j's native
     * {@code CompileConfig.interruptsAfter}, which pauses execution
     * unconditionally after they run — independent of what the agent
     * returned. See {@link WorkflowRoutingFunction}'s Javadoc.
     */
    private static boolean isInterruptEligible(NodeDefinition node) {
        return node.requiresApproval() || node.resumeTrigger() != null;
    }

    /** Strips reserved engine bookkeeping keys so agents/audit log only ever see business state. */
    private static Map<String, Object> businessKeysOnly(Map<String, Object> data) {
        Map<String, Object> copy = new LinkedHashMap<>(data);
        copy.remove(EngineStateKeys.STATUS);
        copy.remove(EngineStateKeys.WAITING_CORRELATION_KEY);
        copy.remove(EngineStateKeys.FAILURE_REASON);
        copy.remove(EngineStateKeys.CURRENT_NODE_ID);
        return copy;
    }
}
