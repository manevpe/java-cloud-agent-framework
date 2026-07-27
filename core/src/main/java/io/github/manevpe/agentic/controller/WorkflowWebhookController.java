package io.github.manevpe.agentic.controller;

import io.github.manevpe.agentic.engine.WorkflowEngineService;
import io.github.manevpe.agentic.workflow.TriggerConditionEvaluator;
import io.github.manevpe.agentic.workflow.WorkflowDefinition;
import io.github.manevpe.agentic.workflow.WorkflowDefinitionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A single, source-agnostic ingress for every external system this
 * framework integrates with (Jira, Slack, GitHub, ...): callers name which
 * workflow or paused thread they're targeting via the URL, so adding a new
 * webhook source never means writing a new controller — only configuring
 * that external system to call one of these two endpoints.
 */
@RestController
@Tag(name = "Workflow webhooks", description = "Generic ingress for starting and resuming workflow threads")
public class WorkflowWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowWebhookController.class);

    private final WorkflowDefinitionRegistry definitionRegistry;
    private final TriggerConditionEvaluator conditionEvaluator;
    private final WorkflowEngineService engineService;

    public WorkflowWebhookController(
            WorkflowDefinitionRegistry definitionRegistry,
            TriggerConditionEvaluator conditionEvaluator,
            WorkflowEngineService engineService) {
        this.definitionRegistry = definitionRegistry;
        this.conditionEvaluator = conditionEvaluator;
        this.engineService = engineService;
    }

    /**
     * Starts a brand-new workflow thread, e.g. a Jira webhook configured to
     * call {@code POST /webhooks/jira-to-pr/start} whenever a ticket
     * changes. The workflow's own {@code trigger.condition} (e.g. "was the
     * ready-for-dev label just added?") is still evaluated against the
     * payload as a safety net — most webhook sources fire on any change
     * matching a broad filter, not just the exact one we care about.
     */
    @Operation(
            summary = "Start a new workflow thread",
            description = "Evaluates the workflow's own trigger condition against the payload before starting it.",
            responses = {
                    @ApiResponse(responseCode = "202", description = "Trigger condition matched; workflow started"),
                    @ApiResponse(responseCode = "200", description = "Trigger condition did not match; ignored"),
                    @ApiResponse(responseCode = "404", description = "No workflow registered under this id")
            })
    @PostMapping("/webhooks/{workflowId}/start")
    public ResponseEntity<Map<String, Object>> start(
            @Parameter(description = "The workflow id, as declared in its YAML file's workflow.id")
            @PathVariable String workflowId,
            @Schema(description = "Raw payload from the external system; keys become initial workflow state")
            @RequestBody Map<String, Object> payload) {
        WorkflowDefinition definition = definitionRegistry.find(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow: " + workflowId));

        if (!conditionEvaluator.matches(definition.trigger(), payload)) {
            log.debug("Webhook for workflow '{}' received but trigger condition did not match: {}",
                    workflowId, payload);
            return ResponseEntity.ok(Map.of("started", false));
        }

        String threadId = engineService.start(workflowId, payload);
        log.info("Started workflow '{}' as thread '{}'", workflowId, threadId);
        return ResponseEntity.accepted().body(Map.of("started", true, "threadId", threadId));
    }

    /**
     * Resumes whichever thread is paused waiting on {@code correlationKey}
     * (a Slack thread id, a GitHub PR's id, ...), merging the inbound
     * event's payload into its state. See {@link WorkflowEngineService}.
     */
    @Operation(
            summary = "Resume a paused workflow thread",
            description = "Looks up the thread waiting on this correlation key and continues it with the event payload merged in.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Thread resumed"),
                    @ApiResponse(responseCode = "404", description = "No thread is waiting on this correlation key")
            })
    @PostMapping("/webhooks/resume/{correlationKey}")
    public ResponseEntity<Map<String, Object>> resume(
            @Parameter(description = "The correlation key a paused thread is waiting on")
            @PathVariable String correlationKey,
            @Schema(description = "Raw event payload from the external system; keys are merged into workflow state")
            @RequestBody Map<String, Object> eventPayload) {
        engineService.resumeByCorrelationKey(correlationKey, eventPayload);
        log.info("Resumed thread waiting on correlation key '{}'", correlationKey);
        return ResponseEntity.ok(Map.of("resumed", true));
    }
}
