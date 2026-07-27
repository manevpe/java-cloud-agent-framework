package io.github.manevpe.agentic.config;

import io.github.manevpe.agentic.plugin.PluginProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables {@link PluginProperties}/{@link AgentProperties} binding. {@code
 * PluginManager} itself is a plain {@code @Component} (see its Javadoc) —
 * this class only exists to register those configuration-properties
 * bindings, same pattern as {@code WorkflowConfigAutoConfiguration}.
 */
@Configuration
@EnableConfigurationProperties({PluginProperties.class, AgentProperties.class})
public class PluginAutoConfiguration {
}
