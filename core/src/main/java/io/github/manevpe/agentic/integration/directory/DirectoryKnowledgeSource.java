package io.github.manevpe.agentic.integration.directory;

import io.github.manevpe.agentic.integration.KnowledgeSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * A {@link KnowledgeSource} backed by one directory of plain-text/Markdown
 * files, whose path is given directly in a workflow node's own {@code
 * knowledgeSources: [{type: directory, path: ...}]} config entry (see
 * {@code NodeKnowledgeSourceResolver}). Every regular file under {@code
 * root} (recursively) is read once at construction time and its raw
 * content handed to the LLM verbatim — deliberately no frontmatter/tag
 * parsing, no keyword indexing or relevance scoring: the file is the unit
 * of context, and {@code query} is ignored entirely, since node config
 * already decides which source(s) are relevant.
 */
public class DirectoryKnowledgeSource implements KnowledgeSource {

    private final List<String> documents;

    public DirectoryKnowledgeSource(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException(
                    "Configured knowledge directory does not exist or is not a directory: " + root);
        }
        try (var paths = Files.walk(root)) {
            this.documents = paths
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .map(this::readFile)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading knowledge directory: " + root, e);
        }
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading knowledge file: " + path, e);
        }
    }

    /**
     * Returns every file's raw content read from {@code root} at startup,
     * unfiltered — {@code query} is intentionally ignored (see class
     * Javadoc).
     */
    @Override
    public List<String> query(String query) {
        return documents;
    }
}
