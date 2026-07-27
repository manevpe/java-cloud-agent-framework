package io.github.manevpe.agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Binds {@code agentic.agents.*} application properties. {@code
 * disabledTypes} lets an operator turn off any built-in agent by its
 * {@code Agent#type()} id (e.g. {@code [pr-comment-gate]}) without
 * removing it from the jar or forking the code. Applies
 * equally to plugin-provided agents, in case a plugin needs to be disabled
 * without removing its jar from the plugin directory.
 */
@ConfigurationProperties(prefix = "agentic.agents")
public record AgentProperties(List<String> disabledTypes) {

    public AgentProperties {
        disabledTypes = disabledTypes == null ? List.of() : List.copyOf(disabledTypes);
    }
}
