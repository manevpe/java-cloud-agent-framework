package io.github.manevpe.agentic.integration.directory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link DirectoryKnowledgeSource} reads every file under its
 * root as-is (no parsing, no relevance filtering) and that {@code query}
 * is entirely ignored, per its documented contract.
 */
class DirectoryKnowledgeSourceTest {

    @Test
    void returnsTheRawContentOfEveryFileUnderTheRootRecursively(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("repo-mapping.md"), "DND Jira project maps to paymenttools/reporting-engine.");
        Path nested = Files.createDirectory(root.resolve("nested"));
        Files.writeString(nested.resolve("notes.txt"), "Some nested onboarding notes.");

        DirectoryKnowledgeSource source = new DirectoryKnowledgeSource(root);

        List<String> results = source.query("irrelevant query text");
        assertThat(results).containsExactlyInAnyOrder(
                "DND Jira project maps to paymenttools/reporting-engine.",
                "Some nested onboarding notes.");
    }

    @Test
    void ignoresTheQueryArgumentEntirely(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("a.md"), "content A");
        DirectoryKnowledgeSource source = new DirectoryKnowledgeSource(root);

        assertThat(source.query("anything")).isEqualTo(source.query("something completely different"));
    }

    @Test
    void returnsEmptyListForAnEmptyDirectory(@TempDir Path root) {
        DirectoryKnowledgeSource source = new DirectoryKnowledgeSource(root);

        assertThat(source.query("q")).isEmpty();
    }

    @Test
    void rejectsANonExistentRoot(@TempDir Path root) {
        Path missing = root.resolve("does-not-exist");

        assertThatThrownBy(() -> new DirectoryKnowledgeSource(missing))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
