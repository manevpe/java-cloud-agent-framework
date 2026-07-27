package io.github.manevpe.agentic.persistence.jpa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.manevpe.agentic.conversation.ConversationRole;
import io.github.manevpe.agentic.conversation.ConversationTurn;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Converts a {@code List<ConversationTurn>} to/from a JSON string for
 * storage in a single {@code json} column — same "opaque blob, no
 * DB-specific JSON operators" approach as {@link JsonMapConverter}, kept as
 * a separate converter only because the shape here is a list of turns
 * rather than a flat map.
 */
@Converter
public class ConversationTurnListConverter implements AttributeConverter<List<ConversationTurn>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<ConversationTurn> turns) {
        try {
            List<Map<String, Object>> raw = (turns == null ? List.<ConversationTurn>of() : turns).stream()
                    .<Map<String, Object>>map(t -> Map.of(
                            "role", t.role().name(), "content", t.content(), "createdAt", t.createdAt().toString()))
                    .toList();
            return MAPPER.writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize conversation turns to JSON", e);
        }
    }

    @Override
    public List<ConversationTurn> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = MAPPER.readValue(dbData, LIST_TYPE);
            return raw.stream()
                    .map(m -> new ConversationTurn(
                            ConversationRole.valueOf((String) m.get("role")),
                            (String) m.get("content"),
                            Instant.parse((String) m.get("createdAt"))))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize conversation turns from JSON", e);
        }
    }
}
