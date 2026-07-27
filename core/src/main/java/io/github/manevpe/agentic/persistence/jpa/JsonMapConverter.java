package io.github.manevpe.agentic.persistence.jpa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Map;

/**
 * Converts a {@code Map<String, Object>} to/from a JSON string for storage in
 * a {@code jsonb} column. Deliberately treats the column as an opaque blob —
 * no JSONB operators or containment queries are used anywhere in the
 * application, which is what keeps this mapping easy to reproduce against a
 * different database's JSON column type later.
 */
@Converter
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        try {
            return MAPPER.writeValueAsString(attribute == null ? Map.of() : attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize state/payload to JSON", e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(dbData, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize JSON from database", e);
        }
    }
}
