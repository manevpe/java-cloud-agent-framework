package io.github.manevpe.agentic.plugin;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Test-only helper that compiles a tiny fixture plugin (one {@code Agent},
 * one {@code EdgeCondition}, one {@code Skill} implementation, each
 * declared via {@code META-INF/services}) and packages it as a real jar —
 * so {@link PluginManager} tests exercise the exact {@code ServiceLoader}
 * discovery path a real external plugin jar would go through, rather than
 * a hand-rolled fake of {@link PluginManager} itself.
 */
public final class PluginFixture {

    public static final String AGENT_TYPE = "fixture-plugin-agent";
    public static final String SKILL_NAME = "fixture-plugin-skill";

    private PluginFixture() {
    }

    /** Compiles and packages the fixture plugin as a jar at {@code jarPath}. */
    public static void buildFixturePluginJar(Path jarPath) {
        try {
            Path workDir = Files.createTempDirectory("plugin-fixture-src");
            Path agentSource = writeSource(workDir, "FixtureAgent", AGENT_SOURCE);
            Path conditionSource = writeSource(workDir, "FixtureCondition", CONDITION_SOURCE);
            Path skillSource = writeSource(workDir, "FixtureSkill", SKILL_SOURCE);

            Path classesDir = Files.createTempDirectory("plugin-fixture-classes");
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            String classpath = System.getProperty("java.class.path");
            int result = compiler.run(null, null, null,
                    "-cp", classpath, "-d", classesDir.toString(),
                    agentSource.toString(), conditionSource.toString(), skillSource.toString());
            if (result != 0) {
                throw new IllegalStateException("Failed to compile fixture plugin source (exit code " + result + ")");
            }

            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
                addClassEntry(jar, classesDir, "testplugin/FixtureAgent.class");
                addClassEntry(jar, classesDir, "testplugin/FixtureCondition.class");
                addClassEntry(jar, classesDir, "testplugin/FixtureSkill.class");
                addTextEntry(jar, "META-INF/services/io.github.manevpe.agentic.agent.Agent",
                        "testplugin.FixtureAgent\n");
                addTextEntry(jar, "META-INF/services/io.github.manevpe.agentic.engine.EdgeCondition",
                        "testplugin.FixtureCondition\n");
                addTextEntry(jar, "META-INF/services/io.github.manevpe.agentic.skill.Skill",
                        "testplugin.FixtureSkill\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path writeSource(Path workDir, String className, String content) throws IOException {
        Path sourceFile = workDir.resolve(className + ".java");
        Files.writeString(sourceFile, content, StandardCharsets.UTF_8);
        return sourceFile;
    }

    private static void addClassEntry(JarOutputStream jar, Path classesDir, String relativePath) throws IOException {
        Path classFile = classesDir.resolve(relativePath.replace('/', java.io.File.separatorChar));
        jar.putNextEntry(new JarEntry(relativePath));
        Files.copy(classFile, jar);
        jar.closeEntry();
    }

    private static void addTextEntry(JarOutputStream jar, String path, String content) throws IOException {
        jar.putNextEntry(new JarEntry(path));
        jar.write(content.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    // Each fixture class must be public with a public no-arg constructor
    // (ServiceLoader requires both — a package-private class or
    // constructor fails with "Unable to get public no-arg constructor"),
    // and each public top-level class needs its own source file.

    private static final String AGENT_SOURCE = """
            package testplugin;

            import io.github.manevpe.agentic.agent.Agent;
            import io.github.manevpe.agentic.agent.AgentResult;
            import io.github.manevpe.agentic.workflow.NodeDefinition;
            import io.github.manevpe.agentic.workflow.WorkflowState;

            public class FixtureAgent implements Agent {
                public String type() {
                    return "fixture-plugin-agent";
                }
                public AgentResult execute(NodeDefinition node, WorkflowState state) {
                    return new AgentResult.Continue(state);
                }
            }
            """;

    private static final String CONDITION_SOURCE = """
            package testplugin;

            import io.github.manevpe.agentic.engine.EdgeCondition;
            import io.github.manevpe.agentic.workflow.WorkflowState;

            public class FixtureCondition implements EdgeCondition {
                public boolean test(WorkflowState state) {
                    return true;
                }
            }
            """;

    private static final String SKILL_SOURCE = """
            package testplugin;

            import io.github.manevpe.agentic.skill.Skill;
            import org.springframework.ai.tool.ToolCallback;

            import java.util.List;

            public class FixtureSkill implements Skill {
                public String name() {
                    return "fixture-plugin-skill";
                }
                public String promptFragment() {
                    return "Fixture skill prompt fragment.";
                }
                public List<ToolCallback> tools() {
                    return List.of();
                }
            }
            """;
}
