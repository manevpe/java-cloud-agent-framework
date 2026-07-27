package io.github.manevpe.agentic.engine;

import io.github.manevpe.agentic.persistence.AuditLogEntry;
import io.github.manevpe.agentic.persistence.AuditLogRepository;
import io.github.manevpe.agentic.persistence.ApprovalStatus;
import io.github.manevpe.agentic.persistence.PendingAction;
import io.github.manevpe.agentic.persistence.PendingActionRepository;
import io.github.manevpe.agentic.persistence.WorkflowThreadRegistry;
import io.github.manevpe.agentic.workflow.WorkflowDefinition;
import io.github.manevpe.agentic.workflow.WorkflowDefinitionRegistry;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * The single entry point for starting and resuming workflow threads. Wraps
 * the LangGraph4j {@link CompiledGraph} lifecycle (compiling once per
 * workflow definition, invoking with a thread-scoped {@link RunnableConfig})
 * behind a small API the webhook/event ingress layer calls.
 *
 * <p>The actual graph invocation runs on a background {@link
 * ExecutorService} (see {@code workflowExecutor}), not on the calling
 * (HTTP request) thread: some nodes now block in-process for a long time
 * (e.g. {@code CodingAgent} driving its in-process sandbox tool-calling
 * loop through an implementation build/test cycle), and running that
 * synchronously inside {@code WorkflowWebhookController}'s request thread
 * would hang the originating Jira/GitHub webhook call for minutes — well
 * past those senders' own timeout/retry windows. {@link #start} and {@link
 * #resumeByCorrelationKey} therefore return immediately once the
 * invocation has been *submitted*, before it necessarily reaches its next
 * pause point or completion; callers observe progress via the audit log
 * and {@code PendingActionRepository}, not via the response of these
 * calls. Any exception escaping the invocation is caught and logged (see
 * {@link #runInBackground}) since there's no synchronous caller left to
 * propagate it to.
 */
@Service
public class WorkflowEngineService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngineService.class);

    private final WorkflowDefinitionRegistry workflowDefinitionRegistry;
    private final WorkflowGraphFactory graphFactory;
    private final PendingActionRepository pendingActionRepository;
    private final WorkflowThreadRegistry threadRegistry;
    private final AuditLogRepository auditLogRepository;
    private final ExecutorService workflowExecutor;
    private final Map<String, CompiledGraph<AgentState>> compiledGraphsByWorkflowId = new ConcurrentHashMap<>();

    public WorkflowEngineService(
            WorkflowDefinitionRegistry workflowDefinitionRegistry,
            WorkflowGraphFactory graphFactory,
            PendingActionRepository pendingActionRepository,
            WorkflowThreadRegistry threadRegistry,
            AuditLogRepository auditLogRepository,
            ExecutorService workflowExecutor) {
        this.workflowDefinitionRegistry = workflowDefinitionRegistry;
        this.graphFactory = graphFactory;
        this.pendingActionRepository = pendingActionRepository;
        this.threadRegistry = threadRegistry;
        this.auditLogRepository = auditLogRepository;
        this.workflowExecutor = workflowExecutor;
    }

    /**
     * Registers a brand-new workflow thread (e.g. from a Jira webhook) and
     * submits it for asynchronous execution — running until it either
     * finishes, pauses (waiting for a Slack reply/GitHub event, or an
     * approval), blocks in-process on a long-running node, or fails.
     *
     * @return the new thread ID — callers must remember this to resume the
     *         workflow later. Returned immediately; does not wait for the
     *         workflow to reach its first pause point.
     */
    public String start(String workflowId, Map<String, Object> triggerPayload) {
        String threadId = UUID.randomUUID().toString();
        threadRegistry.register(threadId, workflowId);

        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        Map<String, Object> payload = triggerPayload == null ? Map.of() : triggerPayload;
        workflowExecutor.execute(() -> runInBackground(
                threadId, workflowId, () -> invoke(workflowId, GraphInput.args(payload), config)));
        return threadId;
    }

    /**
     * Looks up the thread paused at the node identified by {@code
     * correlationKey} (see {@link PendingAction#correlationKey()}) and
     * submits its resumption for asynchronous execution, merging the
     * inbound event's payload into the checkpointed state. Execution
     * continues from wherever the paused node's outgoing edge led — it
     * does not re-execute the paused node itself. The correlation-key
     * lookup itself stays synchronous (fast DB read) so an unknown key
     * still fails fast with a 404 from the controller; only the actual
     * graph invocation is deferred to the background executor.
     */
    public void resumeByCorrelationKey(String correlationKey, Map<String, Object> eventPayload) {
        PendingAction pending = pendingActionRepository.findPendingByCorrelationKey(correlationKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No thread is waiting on correlation key '%s'".formatted(correlationKey)));

        String workflowId = threadRegistry.findWorkflowId(pending.threadId())
                .orElseThrow(() -> new IllegalStateException(
                        "No workflow registered for thread '%s'".formatted(pending.threadId())));

        RunnableConfig config = RunnableConfig.builder()
                .threadId(pending.threadId())
                .build();
        Map<String, Object> payload = eventPayload == null ? Map.of() : eventPayload;

        pendingActionRepository.save(pending.approve("system:resumed"));
        workflowExecutor.execute(() -> runInBackground(
                pending.threadId(), workflowId, () -> invoke(workflowId, GraphInput.resume(payload), config)));
    }

    /**
     * Runs one graph invocation on the background executor, recording
     * whether it paused again afterwards, and logging (rather than
     * throwing) any exception — nothing is left synchronously waiting to
     * catch it once execution has been handed off.
     */
    private void runInBackground(String threadId, String workflowId, java.util.function.Supplier<AgentState> invocation) {
        try {
            AgentState result = invocation.get();
            recordPauseIfAny(threadId, result);
        } catch (Exception e) {
            log.error("Workflow '{}' thread '{}' failed during background execution", workflowId, threadId, e);
            auditLogRepository.append(AuditLogEntry.of(
                    threadId, "ENGINE", "system", "WORKFLOW_EXECUTION_ERROR",
                    Map.of("error", String.valueOf(e.getMessage())), ApprovalStatus.NOT_REQUIRED));
        }
    }

    private AgentState invoke(String workflowId, GraphInput input, RunnableConfig config) {
        WorkflowDefinition definition = workflowDefinitionRegistry.find(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow: " + workflowId));

        CompiledGraph<AgentState> graph = compiledGraphsByWorkflowId
                .computeIfAbsent(workflowId, id -> graphFactory.build(definition));

        // Populate MDC for the duration of this invocation so every log line
        // emitted while running the graph (including from agent/tool code
        // several call-frames deep) can be correlated by threadId — surfaced
        // as a structured field by the JSON console/file encoder (see
        // logging.structured.format in application.yml). traceId is an
        // alias for threadId (same value) so log queries can use whichever
        // name feels more natural — this workflow thread IS the unit of
        // execution being traced end-to-end across agents/tools/LLM calls.
        String threadId = config.threadId().orElse(null);
        MDC.put("threadId", threadId);
        MDC.put("traceId", threadId);
        MDC.put("workflowId", workflowId);
        try {
            return graph.invoke(input, config)
                    .orElseThrow(() -> new IllegalStateException(
                            "Workflow '%s' produced no final state".formatted(workflowId)));
        } finally {
            MDC.remove("threadId");
            MDC.remove("traceId");
            MDC.remove("workflowId");
        }
    }

    private void recordPauseIfAny(String threadId, AgentState finalState) {
        String status = (String) finalState.data().get(EngineStateKeys.STATUS);
        String nodeId = (String) finalState.data().get(EngineStateKeys.CURRENT_NODE_ID);
        String correlationKey = (String) finalState.data().get(EngineStateKeys.WAITING_CORRELATION_KEY);

        if (NodeExecutionStatus.WAITING_FOR_EVENT.name().equals(status)
                || NodeExecutionStatus.WAITING_FOR_APPROVAL.name().equals(status)) {
            pendingActionRepository.save(PendingAction.propose(
                    threadId, nodeId, status, correlationKey, finalState.data()));
        }
    }
}

