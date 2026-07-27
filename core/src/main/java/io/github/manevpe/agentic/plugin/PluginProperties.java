package io.github.manevpe.agentic.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Binds {@code agentic.plugins.*} application properties. {@code
 * directory} is nullable/optional — omitting it (the default) disables
 * plugin loading entirely, so a plain deployment with no external plugins
 * pays zero startup cost or directory-existence requirement.
 */
@ConfigurationProperties(prefix = "agentic.plugins")
public record PluginProperties(Path directory) {
}
