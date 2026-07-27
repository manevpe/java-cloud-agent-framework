package io.github.manevpe.agentic.agent.coding;

import io.github.manevpe.agentic.agent.Agent;
import io.github.manevpe.agentic.agent.AgentResult;
import io.github.manevpe.agentic.integration.GitHubClient;
import io.github.manevpe.agentic.integration.LlmClient;
import io.github.manevpe.agentic.integration.llm.tool.SubmitImplementationResultTool;
import io.github.manevpe.agentic.integration.llm.tool.SubmitPrResponseTool;
import io.github.manevpe.agentic.integration.llm.tool.WorkspaceSetupTool;
import io.github.manevpe.agentic.plugin.PluginContext;
import io.github.manevpe.agentic.plugin.PluginContextAware;
import io.github.manevpe.agentic.workflow.NodeDefinition;
import io.github.manevpe.agentic.workflow.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Owns every coding task in the workflow: turning the finalized plan into
 * a merged code change and opening a PR for it, and — reused for a second
 * node — reacting to PR review feedback (deciding whether it requires a
 * code change, replying, and pushing an amending commit if so). Registered
 * once under {@code coding-agent} and referenced from two YAML nodes with
 * different {@code mode} config values, since {@link
 * io.github.manevpe.agentic.engine.AgentRegistry} resolves one bean per
 * {@link #type()} but nothing stops multiple node ids from naming the same
 * type (see ADR-0006). Like {@code PlanningAgent}, each node's own {@code
 * knowledgeSources: [...]} config is resolved generically by the engine
 * itself before this agent ever runs (see {@code WorkflowNodeAction} and
 * {@code WorkflowState#KNOWLEDGE_CONTEXT}) — this agent just reads that
 * state key and folds it into its coding prompt, so e.g. a Backstage-style
 * repo/domain catalog can inform the implementation the same way it
 * informs planning, with zero agent-side knowledge of how sources are
 * configured or resolved.
 *
 * <ul>
 *   <li>{@code mode: implement} (the {@code implement} node): implements
 *   {@code finalPlan}/{@code plan} against the repository's default
 *   branch and opens a PR via {@link GitHubClient}.</li>
 *   <li>{@code mode: respond-to-review} (the {@code respond-to-pr-comment}
 *   node): merged/closed events just record and stop; otherwise decides
 *   whether the review batch needs a code change, always posts a reply
 *   (low-risk/reversible, auto-executes — see ADR-0004), and — only if a
 *   change is needed — implements it against the PR's existing branch and
 *   pushes an amending commit. No separate approval gate on the amendment
 *   itself; the checkpoint for code changes is the PR review/merge, same
 *   as the initial change (see ADR-0004).</li>
 * </ul>
 *
 * <p>A single class handles both the no-LLM-configured and real LLM-backed
 * cases for both the implementation and the review-response decision —
 * {@link LlmClient} always has exactly one bean ({@code LoggingLlmClient}
 * when {@code agentic.llm.enabled=false}, {@code SpringAiLlmClient} when
 * {@code true}), so this agent always builds the same prompts/tools and
 * simply falls back to a safe default whenever a completion comes back
 * blank (no model configured): implementation reports a failed/untested
 * change (there is no way to actually implement code without a model),
 * and the review decision reproduces this project's original
 * keyword-matching heuristic. This replaced an earlier design that spread
 * this logic across a separate {@code CodeChangeService}/{@code
 * CodeImplementationAssistant}/{@code PrResponseAssistant} strategy layer,
 * and, before that, an async {@code SandboxJobDispatcher} Job dispatched
 * to an opaque container image — real code generation, build, and test
 * execution still happen out-of-process in an isolated {@code
 * SandboxWorkspaceClient} pod (per ADR-0005), but are now driven by this
 * agent's own LLM tool-calling loop (clone, explore, write, run, diff)
 * rather than a flat instructions string handed to a black-box Job.
 *
 * <p>Both modes block in-process for however long the workspace pod's
 * build/test command takes — safe here because the workflow engine
 * invokes this node on its background executor, never on the originating
 * webhook's HTTP request thread (see {@code WorkflowEngineService}).
 */
public class CodingAgent implements Agent, PluginContextAware {

    private static final Logger log = LoggerFactory.getLogger(CodingAgent.class);

    private static final String DEFAULT_REPOSITORY = "unknown/unknown";
    private static final int DEFAULT_MAX_ITERATIONS = 5;
    private static final String MODE_IMPLEMENT = "implement";
    private static final String MODE_RESPOND_TO_REVIEW = "respond-to-review";
    private static final List<String> IMPLEMENT_REQUIRED_TOOLS =
            List.of("file-read", "workspace-setup", "file-edit", "github-api", "http-request");
    private static final List<String> PR_RESPONSE_REQUIRED_TOOLS = List.of("file-read", "workspace-setup");

    private GitHubClient gitHubClient;
    private LlmClient llmClient;
    private WorkspaceSetupTool workspaceSetupTool;
    private SubmitImplementationResultTool submitImplementationResultTool;
    private SubmitPrResponseTool submitPrResponseTool;

    private String codingSystemPrompt;
    private String codingUserPromptTemplate;
    private List<ToolCallback> codingTools;

    private String prResponseSystemPrompt;
    private String prResponseUserPromptTemplate;
    private List<ToolCallback> prResponseTools;

    public CodingAgent() {
    }

    @Override
    public List<String> requiredTools() {
        return Stream.concat(IMPLEMENT_REQUIRED_TOOLS.stream(), PR_RESPONSE_REQUIRED_TOOLS.stream())
                .distinct()
                .toList();
    }

    @Override
    public void setPluginContext(PluginContext context) {
        this.gitHubClient = context.gitHubClient();
        this.llmClient = context.llmClient();
        this.workspaceSetupTool = context.toolRegistry().resolveInstance("workspace-setup", WorkspaceSetupTool.class);
        this.submitImplementationResultTool = new SubmitImplementationResultTool();
        this.submitPrResponseTool = new SubmitPrResponseTool();

        String codingSystemPromptLocation = context.environment().getProperty(
                "agentic.llm.coding.system-prompt-location", "classpath:prompts/coding-system-prompt.st");
        String codingUserPromptLocation = context.environment().getProperty(
                "agentic.llm.coding.user-prompt-location", "classpath:prompts/coding-user-prompt.st");
        this.codingSystemPrompt = readResource(context.resourceLoader().getResource(codingSystemPromptLocation));
        this.codingUserPromptTemplate = readResource(context.resourceLoader().getResource(codingUserPromptLocation));
        this.codingTools = Stream.concat(
                context.toolRegistry().resolveTools(IMPLEMENT_REQUIRED_TOOLS).stream(),
                Arrays.stream(ToolCallbacks.from(submitImplementationResultTool))).toList();

        String prResponseSystemPromptLocation = context.environment().getProperty(
                "agentic.llm.pr-response.system-prompt-location", "classpath:prompts/pr-response-system-prompt.st");
        String prResponseUserPromptLocation = context.environment().getProperty(
                "agentic.llm.pr-response.user-prompt-location", "classpath:prompts/pr-response-user-prompt.st");
        this.prResponseSystemPrompt = readResource(context.resourceLoader().getResource(prResponseSystemPromptLocation));
        this.prResponseUserPromptTemplate = readResource(context.resourceLoader().getResource(prResponseUserPromptLocation));
        this.prResponseTools = Stream.concat(
                context.toolRegistry().resolveTools(PR_RESPONSE_REQUIRED_TOOLS).stream(),
                Arrays.stream(ToolCallbacks.from(submitPrResponseTool))).toList();
    }

    @Override
    public String type() {
        return "coding-agent";
    }

    @Override
    public AgentResult execute(NodeDefinition node, WorkflowState state) {
        String mode = (String) node.config().get("mode");
        if (mode == null) {
            throw new IllegalStateException(
                    "Node '%s' registers agent 'coding-agent' but is missing a required 'mode' config "
                            + "value (expected '%s' or '%s')".formatted(node.id(), MODE_IMPLEMENT, MODE_RESPOND_TO_REVIEW));
        }

        return switch (mode) {
            case MODE_IMPLEMENT -> implement(node, state);
            case MODE_RESPOND_TO_REVIEW -> respondToReview(node, state);
            default -> throw new IllegalStateException(
                    "Node '%s' has unknown coding-agent mode '%s' (expected '%s' or '%s')"
                            .formatted(node.id(), mode, MODE_IMPLEMENT, MODE_RESPOND_TO_REVIEW));
        };
    }

    private AgentResult implement(NodeDefinition node, WorkflowState state) {
        String plan = state.get("finalPlan", String.class)
                .or(() -> state.get("plan", String.class))
                .orElse("");
        String ticketKey = state.get("ticketKey", String.class).orElse("UNKNOWN");
        String summary = state.get("summary", String.class).orElse("");
        // Deliberately no hardcoded default here: which repository a
        // ticket belongs to is determined by the model itself from this
        // node's own knowledge context (e.g. an explicit ticket-project
        // to repository mapping — see knowledge/example-domain/repo-mappings.txt),
        // not pinned in workflow YAML — see implementCodeChange's Javadoc.
        // A node MAY still set config.repository to force a specific
        // repository (skipping inference entirely), useful for a
        // workflow that only ever targets one repository.
        String configuredRepository = (String) node.config().get("repository");

        List<String> knowledge = knowledgeContextFrom(state);
        ImplementationResult implementation =
                implementCodeChange(ticketKey, configuredRepository, null, plan, knowledge);
        String repository = implementation.repository();

        state.put("diff", implementation.diff());
        state.put("testsPassed", implementation.testsPassed());
        state.put("testSummary", implementation.testSummary());
        state.put("changedFiles", implementation.changedFiles());
        state.put("repository", repository);

        if (implementation.testsPassed()) {
            String branchName = "agentic/%s".formatted(ticketKey.toLowerCase());
            String commitMessage = "%s: %s".formatted(ticketKey, summary);
            String prTitle = "%s: %s".formatted(ticketKey, summary);
            String prDescription = "Implements the plan for %s:\n\n%s".formatted(ticketKey, plan);

            String prUrl = gitHubClient.pushBranchAndOpenPullRequest(
                    repository, branchName, commitMessage, implementation.diff(), prTitle, prDescription);

            state.put("prUrl", prUrl);
            // Recorded so respond-to-review mode can push amending commits
            // onto the same branch/repository later.
            state.put("branchName", branchName);
        }

        return new AgentResult.Continue(state);
    }

    private AgentResult respondToReview(NodeDefinition node, WorkflowState state) {
        String eventType = state.get("prEventType", String.class).orElse("review_submitted");
        int maxIterations = ((Number) node.config().getOrDefault("maxIterations", DEFAULT_MAX_ITERATIONS)).intValue();
        state.put("prFeedbackMaxIterations", maxIterations);

        if ("merged".equals(eventType) || "closed".equals(eventType)) {
            state.put("prThreadClosed", true);
            state.put("prClosedReason", eventType);
            state.put("needsAmendment", false);
            return new AgentResult.Continue(state);
        }

        String ticketKey = state.get("ticketKey", String.class).orElse("UNKNOWN");
        String repository = state.get("repository", String.class).orElse(DEFAULT_REPOSITORY);
        String branchName = state.get("branchName", String.class).orElse("");
        String prUrl = state.get("prUrl", String.class).orElse("");
        @SuppressWarnings("unchecked")
        List<String> reviewComments = state.get("reviewComments", List.class).orElse(List.of());

        int iteration = state.get("prFeedbackIteration", Integer.class).orElse(0) + 1;
        state.put("prFeedbackIteration", iteration);

        PrResponseDecision decision = decidePrResponse(repository, prUrl, reviewComments);
        state.put("needsAmendment", decision.needsAmendment());

        gitHubClient.postPullRequestComment(repository, prUrl, decision.reply());

        if (decision.needsAmendment()) {
            String instructions = "Address the following PR review feedback:\n- " + String.join("\n- ", reviewComments);
            List<String> knowledge = knowledgeContextFrom(state);
            ImplementationResult implementation = implementCodeChange(
                    ticketKey, repository, branchName, instructions, knowledge);

            state.put("amendmentPushed", implementation.testsPassed());
            state.put("diff", implementation.diff());
            state.put("testsPassed", implementation.testsPassed());
            state.put("testSummary", implementation.testSummary());
            state.put("changedFiles", implementation.changedFiles());

            if (implementation.testsPassed()) {
                String commitMessage = "Address review feedback (batch #%d)".formatted(iteration);
                gitHubClient.pushAmendingCommit(repository, branchName, commitMessage, implementation.diff());
            }
        }

        return new AgentResult.Continue(state);
    }

    /**
     * Reads this node's own resolved knowledge context, populated
     * generically by the engine (see {@code WorkflowNodeAction}) before
     * this agent ran, from its {@code knowledgeSources: [...]} YAML
     * config — an empty list means this node had no sources configured,
     * by design; there is no implicit "query everything" fallback.
     */
    @SuppressWarnings("unchecked")
    private static List<String> knowledgeContextFrom(WorkflowState state) {
        return (List<String>) (List<?>) state.get(WorkflowState.KNOWLEDGE_CONTEXT, List.class).orElse(List.of());
    }

    /**
     * Runs the LLM tool-calling loop that actually implements {@code
     * instructions}: clones {@code repository} (at {@code ref}, or the
     * default branch if {@code null} — a brand-new implementation vs. an
     * amendment onto an existing PR branch), explores it, writes the
     * change, runs the repository's own build/test command, and retrieves
     * the resulting diff — all via {@link FileEditTool}/{@link
     * WorkspaceSetupTool} against the same isolated workspace pod (see
     * ADR-0005). {@code knowledge} is this node's own resolved knowledge
     * context (e.g. a Backstage-style repo/domain catalog), populated
     * generically by the engine from its knowledgeSources config, same
     * per-node model as {@code PlanningAgent}. Falls back to a failed
     * result if no LLM is configured, since there's no way to actually
     * implement a change without one.
     *
     * <p>{@code repository} is {@code null}/blank for a brand-new
     * implementation (the {@code implement} node deliberately never
     * hardcodes a target repository in workflow config): the model must
     * determine it itself from {@code knowledge} — typically an explicit
     * ticket-project-to-repository mapping supplied as a
     * knowledgeSources entry (see {@code knowledge/example-domain/repo-mappings.txt})
     * — and reports which one it used back via {@code
     * submitImplementationResult}'s own {@code repository} parameter (see
     * {@link SubmitImplementationResultTool}). For an amendment,
     * {@code repository} is already known (recorded in workflow state
     * from the original implementation), so the model is told it
     * directly instead.
     */
    private ImplementationResult implementCodeChange(
            String ticketKey, String repository, String ref, String instructions, List<String> knowledge) {
        boolean repositoryKnown = repository != null && !repository.isBlank();
        String repositoryLine = repositoryKnown
                ? "Repository: " + repository
                : "Repository: not specified — determine it yourself from the knowledge context below "
                        + "(look for an explicit mapping from this ticket's project/domain to a GitHub "
                        + "repository; if genuinely ambiguous, pick the repository whose description best "
                        + "matches the ticket).";
        String cloneInstruction = repositoryKnown
                ? "Use the gitClone tool to clone " + repository + " at the ref above into a sandbox workspace, "
                        + "then listWorkspaceFiles/readWorkspaceFile/searchWorkspace to inspect existing code, "
                        + "writeWorkspaceFile to make the change, and runWorkspaceCommand to build/test it. Call "
                        + "diffWorkspace once you're done to get the final diff to report back."
                : "First narrow down which repository this change belongs to using the knowledge context "
                        + "above plus listGithubOrgRepositories/searchGithubCode/readRepoFile (none of these "
                        + "clone anything) — only once you're confident, use the gitClone tool to clone the "
                        + "single repository you determined into a sandbox workspace, then "
                        + "listWorkspaceFiles/readWorkspaceFile/searchWorkspace to inspect existing code, "
                        + "writeWorkspaceFile to make the change, and runWorkspaceCommand to build/test it. Call "
                        + "diffWorkspace once you're done to get the "
                        + "final diff, and report the repository you used via submitImplementationResult's own "
                        + "repository parameter.";
        String userPrompt = codingUserPromptTemplate
                .replace("{{ticketKey}}", ticketKey)
                .replace("{{repositoryLine}}", repositoryLine)
                .replace("{{ref}}", ref == null || ref.isBlank() ? "(default branch)" : ref)
                .replace("{{instructions}}", instructions)
                .replace("{{knowledge}}", String.join("\n- ", knowledge))
                .replace("{{cloneInstruction}}", cloneInstruction);

        String response;
        try {
            response = llmClient.complete(codingSystemPrompt, userPrompt, codingTools);
        } finally {
            // Same per-call workspace lifecycle as planning (see ADR-0005):
            // the workspace pod this call opened is closed once it returns,
            // never kept alive across the paused workflow's later, separate
            // PR-review turns.
            workspaceSetupTool.closeAllOpenedInCurrentCall();
        }

        Optional<SubmitImplementationResultTool.Result> result = submitImplementationResultTool.consumeResult();
        if (result.isPresent()) {
            SubmitImplementationResultTool.Result r = result.get();
            // A workflow-pinned repository always wins over whatever the
            // model reports (defends against a model ignoring an explicit
            // pin); otherwise trust the model's own determination.
            String resolvedRepository = repositoryKnown
                    ? repository
                    : (r.repository() == null || r.repository().isBlank()) ? repository : r.repository();
            return new ImplementationResult(resolvedRepository, r.testsPassed(), r.diff(), r.testSummary(), r.changedFiles());
        }

        if (response == null || response.isBlank()) {
            return ImplementationResult.failed(repository,
                    "No LLM configured (agentic.llm.enabled=false) — cannot implement code changes without one.");
        }

        log.error("LLM did not call submitImplementationResult; final response was: {}", response);
        return ImplementationResult.failed(repository,
                "LLM did not submit an implementation result via the submitImplementationResult tool");
    }

    /**
     * Decides whether a batch of PR review comments genuinely requires a
     * code change and drafts a reply, optionally inspecting the target
     * repository via the same read-only tools planning uses. Falls back
     * to this project's original keyword-matching heuristic if no LLM is
     * configured.
     */
    private PrResponseDecision decidePrResponse(String repository, String prUrl, List<String> reviewComments) {
        String userPrompt = prResponseUserPromptTemplate
                .replace("{{repository}}", repository)
                .replace("{{prUrl}}", prUrl)
                .replace("{{reviewComments}}", String.join("\n- ", reviewComments));

        String response;
        try {
            response = llmClient.complete(prResponseSystemPrompt, userPrompt, prResponseTools);
        } finally {
            workspaceSetupTool.closeAllOpenedInCurrentCall();
        }

        Optional<SubmitPrResponseTool.Result> result = submitPrResponseTool.consumeResult();
        if (result.isPresent()) {
            SubmitPrResponseTool.Result r = result.get();
            return new PrResponseDecision(r.needsAmendment(), r.reply());
        }

        if (response == null || response.isBlank()) {
            return heuristicPrResponseDecision(reviewComments);
        }

        log.error("LLM did not call submitPrResponse; final response was: {}", response);
        throw new IllegalStateException(
                "LLM did not submit a PR response decision via the submitPrResponse tool");
    }

    private static PrResponseDecision heuristicPrResponseDecision(List<String> reviewComments) {
        boolean needsAmendment = reviewComments.stream().anyMatch(CodingAgent::requestsCodeChange);
        String bulletList = reviewComments.isEmpty()
                ? "(no comment text provided)"
                : String.join("\n- ", reviewComments);
        String reply = needsAmendment
                ? "Thanks for the review — addressing the following in one commit:\n- %s".formatted(bulletList)
                : "Thanks for the review — noted, no code changes needed for:\n- %s".formatted(bulletList);
        return new PrResponseDecision(needsAmendment, reply);
    }

    private static boolean requestsCodeChange(String comment) {
        String lower = comment == null ? "" : comment.toLowerCase();
        return lower.contains("please") || lower.contains("could you")
                || lower.contains("can you") || lower.contains("fix")
                || lower.contains("change") || lower.contains("update");
    }

    private static String readResource(Resource resource) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read prompt template from " + resource, e);
        }
    }

    private record ImplementationResult(
            String repository, boolean testsPassed, String diff, String testSummary, List<String> changedFiles) {
        static ImplementationResult failed(String repository, String testSummary) {
            return new ImplementationResult(repository, false, "", testSummary, List.of());
        }
    }

    private record PrResponseDecision(boolean needsAmendment, String reply) {
    }
}
