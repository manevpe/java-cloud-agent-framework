package io.github.manevpe.agentic.workflow;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mutable, JSON-serializable key/value bag carried through a workflow instance
 * as it moves between nodes. Deliberately storage-agnostic: adapters
 * (Postgres, Spanner, ...) are responsible for serializing this to whatever
 * column type they use (e.g. JSON/JSONB/JSON in Spanner) without this class
 * knowing or caring which database is in use.
 */
public final class WorkflowState {

    private final Map<String, Object> values;

    /**
     * Reserved key the engine writes into a node's input state right before
     * invoking its agent (see {@code WorkflowNodeAction}): every source
     * listed in the node's own {@code knowledgeSources: [...]} YAML config
     * is resolved and queried generically by the engine itself, so any
     * agent — present or future — just reads this key like any other piece
     * of state, with no knowledge of {@code NodeKnowledgeSourceResolver} or
     * of how sources are configured. An empty/missing list on a node means
     * an empty list here, by design — no implicit "all sources" fallback.
     */
    public static final String KNOWLEDGE_CONTEXT = "knowledgeContext";

    public WorkflowState() {
        this.values = new HashMap<>();
    }

    public WorkflowState(Map<String, Object> initial) {
        this.values = new HashMap<>(initial);
    }

    public static WorkflowState empty() {
        return new WorkflowState();
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = values.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!type.isInstance(value)) {
            throw new IllegalStateException(
                    "State key '%s' expected type %s but was %s".formatted(key, type, value.getClass()));
        }
        return Optional.of(type.cast(value));
    }

    public Object getRaw(String key) {
        return values.get(key);
    }

    public WorkflowState put(String key, Object value) {
        values.put(key, value);
        return this;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public Map<String, Object> asMap() {
        return Map.copyOf(values);
    }

    /** Returns a defensive copy so callers can't mutate a checkpointed snapshot. */
    public WorkflowState copy() {
        return new WorkflowState(this.values);
    }
}
