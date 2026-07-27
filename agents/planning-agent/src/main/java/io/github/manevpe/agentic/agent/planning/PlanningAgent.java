package io.github.manevpe.agentic.agent.planning;

import io.github.manevpe.agentic.agent.Agent;
import io.github.manevpe.agentic.agent.AgentResult;
import io.github.manevpe.agentic.integration.LlmClient;
import io.github.manevpe.agentic.integration.llm.tool.FileReadTool;
import io.github.manevpe.agentic.integration.llm.tool.WorkspaceSetupTool;
import io.github.manevpe.agentic.plugin.PluginContext;
import io.github.manevpe.agentic.plugin.PluginContextAware;
import io.github.manevpe.agentic.workflow.NodeDefinition;
import io.github.manevpe.agentic.workflow.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a ticket's requirements plus relevant domain knowledge and drafts
 * an implementation plan, flagging any open questions blocking it.
 * Registered under {@code planning-agent} in workflow YAML.
 *
 * <p>A single class handles both the no-LLM-configured case and the real
 * LLM-backed case — {@link LlmClient} always has exactly one bean ({@code
 * LoggingLlmClient} when {@code agentic.llm.enabled=false}, {@code
 * SpringAiLlmClient} when {@code true}, see their Javadoc), so this agent
 * always builds the same prompt and offers the same tools ({@link
 * FileReadTool}, {@link WorkspaceSetupTool}'s read-only {@code
 * gitClone}/list/read/search, see ADR-0005) and simply falls back
 * to this project's original deterministic heuristics (blank description /
 * a description containing {@code TBD} or {@code ?} triggers an open
 * question) whenever the completion comes back blank — i.e. no model
 * configured. This replaced an earlier design that kept two separate
 * {@code PlanningAssistant} implementation classes (one heuristic, one
 * LLM-backed) behind a strategy interface purely to express that same
 * fallback.
 *
 * <p>Clarification is a genuine multi-round loop, not a single fixed
 * exchange: every time this node re-runs after a Slack reply resumes the
 * workflow (see {@code human-gate}'s {@code await-clarifications} node
 * looping back into {@code plan} in {@code jira-to-pr.yaml}), it appends
 * the previous round's question(s)/answer onto {@code
 * clarificationHistory} in state and redrafts the plan considering the
 * full history so far, rather than just string-concatenating the raw
 * reply onto the original draft. If the redraft still has open questions,
 * routing sends it back through another Slack round — up to {@code
 * maxClarificationRounds} node config (default {@value
 * #DEFAULT_MAX_CLARIFICATION_ROUNDS}, configurable per node in workflow
 * YAML), after which it proceeds anyway
 * with a note about what's still unresolved, so a persistently
 * unanswerable/ambiguous ticket can never wedge the workflow forever.
 */
public class PlanningAgent implements Agent, PluginContextAware {

    private static final Logger log = LoggerFactory.getLogger(PlanningAgent.class);
    private static final int DEFAULT_MAX_CLARIFICATION_ROUNDS = 12;
    private static final List<String> REQUIRED_TOOLS = List.of("file-read", "workspace-setup");

    private LlmClient llmClient;
    private String systemPrompt;
    private String userPromptTemplate;
    private List<ToolCallback> tools;
    private WorkspaceSetupTool workspaceSetupTool;

    public PlanningAgent() {
    }

    @Override
    public List<String> requiredTools() {
        return REQUIRED_TOOLS;
    }

    @Override
    public void setPluginContext(PluginContext context) {
        this.llmClient = context.llmClient();
        this.workspaceSetupTool = context.toolRegistry().resolveInstance("workspace-setup", WorkspaceSetupTool.class);
        String systemPromptLocation = context.environment().getProperty(
                "agentic.llm.planning.system-prompt-location", "classpath:prompts/planning-system-prompt.st");
        String userPromptLocation = context.environment().getProperty(
                "agentic.llm.planning.user-prompt-location", "classpath:prompts/planning-user-prompt.st");
        this.systemPrompt = readResource(context.resourceLoader().getResource(systemPromptLocation));
        this.userPromptTemplate = readResource(context.resourceLoader().getResource(userPromptLocation));
        this.tools = context.toolRegistry().resolveTools(REQUIRED_TOOLS);
    }

    @Override
    public String type() {
        return "planning-agent";
    }

    @Override
    @SuppressWarnings("unchecked")
    public AgentResult execute(NodeDefinition node, WorkflowState state) {
        String ticketKey = state.get("ticketKey", String.class).orElse("UNKNOWN");
        String summary = state.get("summary", String.class).orElse("");
        String description = state.get("description", String.class).orElse("");
        int maxRounds = ((Number) node.config().getOrDefault(
                "maxClarificationRounds", DEFAULT_MAX_CLARIFICATION_ROUNDS)).intValue();

        // Checkpoint round-trips deserialize nested collections as generic
        // Maps/Lists (no polymorphic typing), so history is always read back
        // as List<Map<String,Object>>, never as ClarificationRound — see
        // ClarificationRound#toStateEntry/#fromStateEntry.
        List<Map<String, Object>> history = new ArrayList<>(
                (List<Map<String, Object>>) (List<?>) state.get("clarificationHistory", List.class).orElse(List.of()));

        String slackAnswer = state.get("slackAnswer", String.class).orElse(null);
        if (slackAnswer != null && !slackAnswer.isBlank()) {
            List<String> previousQuestions = (List<String>) (List<?>) state.get("openQuestions", List.class)
                    .orElse(List.of());
            history.add(ClarificationRound.toStateEntry(previousQuestions, slackAnswer));
            state.put("clarificationHistory", history);
        }

        // Resolved generically by the engine (WorkflowNodeAction) from this
        // node's own knowledgeSources config, before this agent ever runs —
        // see WorkflowState#KNOWLEDGE_CONTEXT's Javadoc.
        List<String> knowledge = (List<String>) (List<?>) state.get(WorkflowState.KNOWLEDGE_CONTEXT, List.class)
                .orElse(List.of());
        PlanDraft draft = draftPlan(ticketKey, summary, description, knowledge, history);

        boolean roundsExhausted = !draft.openQuestions().isEmpty() && history.size() >= maxRounds;
        String finalPlanText = roundsExhausted
                ? draft.planText() + "\n\nNote: proceeding after reaching the maximum of "
                        + maxRounds + " clarification round(s) with unresolved questions:\n- "
                        + String.join("\n- ", draft.openQuestions())
                : draft.planText();
        List<String> finalOpenQuestions = roundsExhausted ? List.of() : draft.openQuestions();

        state.put("plan", finalPlanText);
        state.put("finalPlan", finalPlanText);
        state.put("openQuestions", finalOpenQuestions);
        state.put("hasOpenQuestions", !finalOpenQuestions.isEmpty());
        return new AgentResult.Continue(state);
    }

    private PlanDraft draftPlan(
            String ticketKey, String summary, String description, List<String> knowledge,
            List<Map<String, Object>> history) {
        String userPrompt = userPromptTemplate
                .replace("{{ticketKey}}", ticketKey)
                .replace("{{summary}}", summary)
                .replace("{{description}}", description)
                .replace("{{knowledge}}", String.join("\n- ", knowledge))
                .replace("{{clarificationHistory}}", renderClarificationHistory(history));

        String response;
        try {
            response = llmClient.complete(systemPrompt, userPrompt, tools);
        } finally {
            // Workspaces opened via gitClone during this call are scoped to
            // a single planning turn (see ADR-0005) — never kept alive
            // across the async gap while a paused workflow waits on a
            // human's Slack reply.
            workspaceSetupTool.closeAllOpenedInCurrentCall();
        }

        if (response == null || response.isBlank()) {
            return heuristicPlanDraft(ticketKey, summary, description, knowledge, history);
        }

        return parsePlanDraft(response);
    }

    /**
     * Parses the plain-text {@code STATUS: READY}/{@code STATUS:
     * NEEDS_CLARIFICATION} marker format (see {@code
     * planning-system-prompt.st}) — deliberately not JSON: the plan and
     * the open questions are mutually exclusive (a plan with open
     * questions isn't ready), so there is no structured multi-field
     * payload to encode here, only a single free-text blob whose content
     * would otherwise need fragile JSON-string escaping for no benefit.
     */
    private static PlanDraft parsePlanDraft(String response) {
        String trimmed = response.strip();
        if (trimmed.regionMatches(true, 0, "STATUS: READY", 0, "STATUS: READY".length())) {
            String planText = trimmed.substring("STATUS: READY".length()).strip();
            return new PlanDraft(planText, List.of());
        }
        if (trimmed.regionMatches(true, 0, "STATUS: NEEDS_CLARIFICATION", 0, "STATUS: NEEDS_CLARIFICATION".length())) {
            String body = trimmed.substring("STATUS: NEEDS_CLARIFICATION".length()).strip();
            List<String> questions = body.lines()
                    .map(String::strip)
                    .filter(line -> !line.isBlank())
                    .map(line -> line.startsWith("-") ? line.substring(1).strip() : line)
                    .toList();
            return new PlanDraft("", questions);
        }
        log.error("LLM plan-draft response did not start with a recognized STATUS marker: {}", response);
        throw new IllegalStateException(
                "LLM returned a response that could not be parsed as a plan draft (missing STATUS marker)");
    }

    private static String renderClarificationHistory(List<Map<String, Object>> history) {
        if (history.isEmpty()) {
            return "(no clarification rounds yet)";
        }
        StringBuilder sb = new StringBuilder();
        int round = 1;
        for (Map<String, Object> entry : history) {
            ClarificationRound r = ClarificationRound.fromStateEntry(entry);
            sb.append("Round ").append(round++).append(":\n")
                    .append("Q: ").append(String.join(" / ", r.questions())).append('\n')
                    .append("A: ").append(r.answer()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Reproduces this project's original deterministic heuristics, extended
     * to actually incorporate clarification answers instead of ignoring
     * them: the original blank/TBD/"?" checks only ever look at the
     * ticket's own description on the very first round (before any answer
     * exists); every subsequent round instead re-checks only the most
     * recent answer, so a concrete answer resolves the plan but an answer
     * that itself contains "TBD"/"?" correctly triggers another round.
     */
    private static PlanDraft heuristicPlanDraft(
            String ticketKey, String summary, String description, List<String> knowledge,
            List<Map<String, Object>> history) {
        String clarifications = history.isEmpty()
                ? ""
                : "\n\nClarifications from the team:\n" + history.stream()
                        .map(ClarificationRound::fromStateEntry)
                        .map(r -> "- Q: %s\n  A: %s".formatted(String.join(" / ", r.questions()), r.answer()))
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");

        String plan = """
                Plan for %s: %s

                Requirements:
                %s

                Relevant knowledge:
                - %s
                """.formatted(ticketKey, summary, description, String.join("\n- ", knowledge)) + clarifications;

        List<String> openQuestions = history.isEmpty()
                ? detectOpenQuestions(description)
                : detectOpenQuestions(ClarificationRound.fromStateEntry(history.get(history.size() - 1)).answer());

        return new PlanDraft(plan, openQuestions);
    }

    private static List<String> detectOpenQuestions(String description) {
        List<String> questions = new ArrayList<>();
        if (description.isBlank()) {
            questions.add("The ticket has no description — what should this change actually do?");
        }
        if (description.contains("TBD") || description.contains("?")) {
            questions.add("The description contains an open question or TBD — please clarify before implementation.");
        }
        return questions;
    }

    private static String readResource(Resource resource) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read prompt template from " + resource, e);
        }
    }

    private record PlanDraft(String planText, List<String> openQuestions) {
    }

    /**
     * One clarification round: the question(s) posted to Slack and the
     * team's reply. Stored in {@link WorkflowState} as a plain {@code
     * Map<String, Object>} (via {@link #toStateEntry}/{@link
     * #fromStateEntry}) rather than this record directly, since state
     * round-trips through JSON on every checkpoint — see {@code
     * WorkflowState}'s Javadoc.
     */
    @SuppressWarnings("unchecked")
    private record ClarificationRound(List<String> questions, String answer) {

        static Map<String, Object> toStateEntry(List<String> questions, String answer) {
            return Map.of("questions", questions, "answer", answer);
        }

        static ClarificationRound fromStateEntry(Map<String, Object> entry) {
            List<String> questions = (List<String>) (List<?>) entry.getOrDefault("questions", List.of());
            String answer = String.valueOf(entry.getOrDefault("answer", ""));
            return new ClarificationRound(questions, answer);
        }
    }
}
