package io.github.manevpe.agentic.integration;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves the {@code humanInteraction.provider} name referenced by a
 * workflow YAML node's config (e.g. {@code slack}) to the registered
 * {@link HumanInteractionClient} bean of that name — same flat
 * name-to-bean resolution pattern as {@code AgentRegistry}/{@code
 * ConditionRegistry}. Adding a new messaging provider never requires
 * touching this class, only registering a new {@link HumanInteractionClient}
 * bean under a new {@link HumanInteractionClient#provider()} name.
 */
@Component
public class HumanInteractionClientRegistry {

    private final Map<String, HumanInteractionClient> clientsByProvider;

    public HumanInteractionClientRegistry(List<HumanInteractionClient> clients) {
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toUnmodifiableMap(HumanInteractionClient::provider, c -> c));
    }

    public HumanInteractionClient resolve(String provider) {
        HumanInteractionClient client = clientsByProvider.get(provider);
        if (client == null) {
            throw new IllegalStateException(
                    "No HumanInteractionClient registered for provider '%s'. Registered providers: %s"
                            .formatted(provider, clientsByProvider.keySet()));
        }
        return client;
    }
}
