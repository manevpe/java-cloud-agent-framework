package io.github.manevpe.agentic.integration.slack;

import io.github.manevpe.agentic.integration.HumanInteractionClient;
import io.github.manevpe.agentic.integration.SlackClient;
import org.springframework.stereotype.Component;

/**
 * Adapts the existing {@link SlackClient} port to the generic {@link
 * HumanInteractionClient} port, registered under the {@code "slack"}
 * provider name. This is the only implementation today — adding a new
 * messaging system later (Teams, Discord, ...) means implementing a
 * sibling {@link HumanInteractionClient} under its own provider name, not
 * touching this class, {@link SlackClient}, or any agent/tool code.
 */
@Component
public class SlackHumanInteractionClient implements HumanInteractionClient {

    static final String PROVIDER = "slack";

    private final SlackClient slackClient;

    public SlackHumanInteractionClient(SlackClient slackClient) {
        this.slackClient = slackClient;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String ask(String target, String question) {
        return slackClient.postThread(target, question);
    }
}
