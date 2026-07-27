package io.github.manevpe.agentic.engine;

import io.github.manevpe.agentic.workflow.EdgeDefinition;
import io.github.manevpe.agentic.workflow.WorkflowDefinition;
import io.github.manevpe.agentic.workflow.WorkflowState;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.EdgeAction;
import org.bsc.langgraph4j.state.AgentState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides, after a node has finished executing, which node runs next by
 * evaluating the workflow YAML's {@link EdgeDefinition}s for this node in
 * order: the first matching (unconditional, or whose named {@link
 * EdgeCondition} bean returns {@code true}) determines the next node. If
 * none match — or the node failed — the run ends ({@code END}).
 *
 * <p>Routing always computes the "real" next node regardless of whether the
 * node paused (waiting for an event/approval): pausing is handled natively
 * by LangGraph4j's {@code CompileConfig.interruptsAfter} mechanism (see
 * {@link WorkflowGraphFactory}), which stops graph execution right after a
 * designated node runs. With {@code CompileConfig.interruptBeforeEdge(true)}
 * (also set in {@link WorkflowGraphFactory}), this function's evaluation
 * itself is deferred until resume time rather than run immediately after
 * the node — so edges gated on data that only arrives via the resume
 * payload (e.g. a sandbox Job's async callback) see that data already
 * merged into state when they're evaluated, instead of stale pre-callback
 * state. Resuming via {@code CompiledGraph.invoke(GraphInput.resume(
 * eventPayload), config)} merges the event's data into state and then
 * triggers this deferred routing evaluation.
 */
class WorkflowRoutingFunction implements EdgeAction<AgentState> {

    private final String nodeId;
    private final List<EdgeDefinition> outgoingEdges;
    private final ConditionRegistry conditionRegistry;

    WorkflowRoutingFunction(String nodeId, WorkflowDefinition definition, ConditionRegistry conditionRegistry) {
        this.nodeId = nodeId;
        this.outgoingEdges = definition.edgesFrom(nodeId);
        this.conditionRegistry = conditionRegistry;
    }

    @Override
    public String apply(AgentState state) {
        String status = (String) state.data().get(EngineStateKeys.STATUS);
        if (NodeExecutionStatus.FAILED.name().equals(status)) {
            return StateGraph.END;
        }

        WorkflowState workflowState = new WorkflowState(state.data());
        for (EdgeDefinition edge : outgoingEdges) {
            if (edge.isUnconditional() || conditionRegistry.resolve(edge.condition()).test(workflowState)) {
                return edge.to();
            }
        }
        return StateGraph.END;
    }

    /** Every value this routing function can return, for {@code addConditionalEdges}'s mapping. */
    Map<String, String> possibleOutcomes() {
        Map<String, String> mappings = new LinkedHashMap<>();
        for (EdgeDefinition edge : outgoingEdges) {
            mappings.put(edge.to(), edge.to());
        }
        mappings.put(StateGraph.END, StateGraph.END);
        return mappings;
    }

    String nodeId() {
        return nodeId;
    }
}
