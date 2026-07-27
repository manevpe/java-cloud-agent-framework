package io.github.manevpe.agentic.config;

import io.github.manevpe.agentic.workflow.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowConfigLoaderTest {

    private final WorkflowConfigLoader loader = new WorkflowConfigLoader();

    @Test
    void parsesJiraToPrWorkflowFromClasspath() {
        try (InputStream in = getClass().getResourceAsStream("/workflows/jira-to-pr.yaml")) {
            WorkflowDefinition definition = loader.load(in, "jira-to-pr.yaml");

            assertThat(definition.id()).isEqualTo("jira-to-pr");
            assertThat(definition.trigger().type()).isEqualTo("webhook");
            assertThat(definition.trigger().source()).isEqualTo("jira");
            assertThat(definition.nodes()).hasSize(6);
            assertThat(definition.edges()).hasSize(7);

            assertThat(definition.node("plan").agent()).isEqualTo("planning-agent");
            assertThat(definition.node("pr-feedback-loop").requiresApproval()).isTrue();
            assertThat(definition.node("pr-feedback-loop").resumeTrigger().source()).isEqualTo("github");

            assertThat(definition.edgesFrom("plan"))
                    .extracting("to", "condition")
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("await-clarifications", "hasOpenQuestions"),
                            org.assertj.core.groups.Tuple.tuple("post-plan", "noOpenQuestions"));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void loadsAllYamlFilesInADirectory(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("jira-to-pr.yaml");
        try (InputStream in = getClass().getResourceAsStream("/workflows/jira-to-pr.yaml")) {
            java.nio.file.Files.copy(in, file);
        }

        List<WorkflowDefinition> definitions = loader.loadAll(tempDir);

        assertThat(definitions).hasSize(1);
        assertThat(definitions.get(0).id()).isEqualTo("jira-to-pr");
    }

    @Test
    void rejectsEdgeReferencingUnknownNode() {
        String badYaml = """
                workflow:
                  id: broken
                  trigger:
                    type: webhook
                    source: jira
                  nodes:
                    - id: only-node
                      agent: some-agent
                  edges:
                    - from: only-node
                      to: does-not-exist
                """;

        assertThatThrownBy(() -> loader.load(
                new java.io.ByteArrayInputStream(badYaml.getBytes()), "broken.yaml"))
                .isInstanceOf(WorkflowConfigException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void rejectsMissingRequiredFields() {
        String badYaml = """
                workflow:
                  trigger:
                    type: webhook
                    source: jira
                  nodes:
                    - id: only-node
                      agent: some-agent
                """;

        assertThatThrownBy(() -> loader.load(
                new java.io.ByteArrayInputStream(badYaml.getBytes()), "broken.yaml"))
                .isInstanceOf(WorkflowConfigException.class)
                .hasMessageContaining("id");
    }
}
