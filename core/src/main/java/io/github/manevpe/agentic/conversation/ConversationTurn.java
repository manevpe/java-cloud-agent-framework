package io.github.manevpe.agentic.conversation;

import java.time.Instant;

/** A single message in a {@link ConversationSession}'s transcript. */
public record ConversationTurn(ConversationRole role, String content, Instant createdAt) {

    public static ConversationTurn of(ConversationRole role, String content) {
        return new ConversationTurn(role, content, Instant.now());
    }
}
