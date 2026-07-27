package com.example.plugin;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Template for a custom LLM-invocable tool, exposed via Spring AI's {@link
 * Tool @Tool} annotation — the same pattern the framework's own built-in
 * tools use (see {@code FileReadTool}, {@code FileEditTool}). A tool is
 * not itself a {@code ServiceLoader}-discovered type; it's only reachable
 * by the framework once it's bundled into a {@link ExampleToolBundle}'s
 * {@code tools()} list (see that class), which *is* discovered via
 * {@code ServiceLoader}.
 */
public class ExampleTool {

    @Tool(description = "Looks up an example fact for the given topic. Replace with real logic.")
    public String lookUpFact(@ToolParam(description = "the topic to look up") String topic) {
        return "Example fact about " + topic;
    }
}
