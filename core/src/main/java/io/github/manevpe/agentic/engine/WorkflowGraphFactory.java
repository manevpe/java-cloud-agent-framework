package io.github.manevpe.agentic.engine;

import io.github.manevpe.agentic.integration.knowledge.NodeKnowledgeSourceResolver;
import io.github.manevpe.agentic.persistence.AuditLogRepository;
import io.github.manevpe.agentic.workflow.NodeDefinition;
import io.github.manevpe.agentic.workflow.WorkflowDefinition;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds and compiles a LangGraph4j {@link StateGraph} from a parsed
 * {@link WorkflowDefinition} — this is the single place workflow YAML
 * "becomes" an executable graph. Adding a new workflow means authoring a
 * new YAML file, not writing new engine code.
 *
 * <p>By convention, the first node listed in the YAML {@code nodes:} array
 * is the graph's entry point. Nodes marked {@code requiresApproval: true}
 * or with a {@code resumeTrigger} are registered with LangGraph4j's native
 * {@code CompileConfig.interruptsAfter(...)}: execution stops right after
 * such a node runs (before advancing to its computed next node), and the
 * checkpoint written for it becomes the durable "paused, waiting on X"
 * record. Resuming is a plain {@code CompiledGraph.invoke(GraphInput.resume(
 * eventPayload), config)} call with the same {@code threadId}.
 */
@Component
class WorkflowGraphFactory {

    private final AgentRegistry agentRegistry;
    private final ConditionRegistry conditionRegistry;
    private final AuditLogRepository auditLogRepository;
    private final BaseCheckpointSaver checkpointSaver;
    private final NodeKnowledgeSourceResolver knowledgeSourceResolver;

    WorkflowGraphFactory(
            AgentRegistry agentRegistry,
            ConditionRegistry conditionRegistry,
            AuditLogRepository auditLogRepository,
            BaseCheckpointSaver checkpointSaver,
            NodeKnowledgeSourceResolver knowledgeSourceResolver) {
        this.agentRegistry = agentRegistry;
        this.conditionRegistry = conditionRegistry;
        this.auditLogRepository = auditLogRepository;
        this.checkpointSaver = checkpointSaver;
        this.knowledgeSourceResolver = knowledgeSourceResolver;
    }

    CompiledGraph<AgentState> build(WorkflowDefinition definition) {
        try {
            StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);

            for (NodeDefinition node : definition.nodes()) {
                graph.addNode(node.id(), new WorkflowNodeAction(
                        node, agentRegistry.resolve(node.agent()), auditLogRepository, knowledgeSourceResolver));
            }

            String entryNodeId = definition.nodes().get(0).id();
            graph.addEdge(StateGraph.START, entryNodeId);

            for (NodeDefinition node : definition.nodes()) {
                WorkflowRoutingFunction routing =
                        new WorkflowRoutingFunction(node.id(), definition, conditionRegistry);
                graph.addConditionalEdges(
                        node.id(), AsyncEdgeAction.edge_async(routing), routing.possibleOutcomes());
            }

            List<String> interruptsAfter = new ArrayList<>();
            for (NodeDefinition node : definition.nodes()) {
                if (node.requiresApproval() || node.resumeTrigger() != null) {
                    interruptsAfter.add(node.id());
                }
            }

            return graph.compile(CompileConfig.builder()
                    .checkpointSaver(checkpointSaver)
                    .interruptsAfter(interruptsAfter)
                    // Defers routing-edge evaluation until resume time instead of right
                    // after the node runs: without this, edges gated on data that only
                    // arrives via the resume payload (e.g. sandboxTestsPassed, populated
                    // by CodingAgent's async callback) would always see it missing, since
                    // the "real" next node would otherwise be computed from stale
                    // pre-callback state before the pause even happens. See CompiledGraph's
                    // interruptBeforeEdge handling.
                    .interruptBeforeEdge(true)
                    .build());
        } catch (GraphStateException e) {
            throw new IllegalStateException(
                    "Failed to build executable graph for workflow '%s': %s"
                            .formatted(definition.id(), e.getMessage()), e);
        }
    }
}
