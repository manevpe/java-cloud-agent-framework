package io.github.manevpe.agentic.persistence.jpa;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Postgres-backed implementation of LangGraph4j's {@link
 * org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver} SPI. This is the
 * single source of truth for workflow progress: every node execution
 * durably persists a checkpoint here, and resuming a paused thread (Slack
 * reply, GitHub webhook, approval decision) is just invoking the compiled
 * graph again with the same {@code threadId}.
 *
 * <p>Replaces an earlier bespoke {@code WorkflowInstance}/{@code
 * WorkflowInstanceRepository} model — rather than maintaining our
 * own parallel state-machine and persistence contract, we let LangGraph4j
 * own checkpointing and only plug in the storage backend.
 */
@Component
public class JpaCheckpointSaver extends AbstractCheckpointSaver {

    /**
     * {@code sequence} only needs to be monotonically increasing per JVM
     * instance to order checkpoints within a thread — it is not a
     * cross-process identity/serial column, keeping the schema portable.
     */
    private final AtomicLong sequenceGenerator = new AtomicLong(System.nanoTime());

    private final CheckpointJpaRepository jpaRepository;

    JpaCheckpointSaver(CheckpointJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) throws Exception {
        String threadId = threadId(config);
        List<CheckpointEntity> entities = jpaRepository.findByThreadIdOrderBySequenceDesc(threadId);
        LinkedList<Checkpoint> checkpoints = new LinkedList<>();
        for (CheckpointEntity entity : entities) {
            checkpoints.add(toCheckpoint(entity));
        }
        return checkpoints;
    }

    @Override
    protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint)
            throws Exception {
        jpaRepository.save(toEntity(threadId(config), checkpoint));
    }

    @Override
    protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint)
            throws Exception {
        jpaRepository.save(toEntity(threadId(config), checkpoint));
    }

    @Override
    protected Tag releaseCheckpoints(RunnableConfig config, LinkedList<Checkpoint> checkpoints) throws Exception {
        String threadId = threadId(config);
        jpaRepository.deleteByThreadId(threadId);
        return new Tag(threadId, checkpoints);
    }

    private CheckpointEntity toEntity(String threadId, Checkpoint checkpoint) {
        return new CheckpointEntity(
                checkpoint.getId(),
                threadId,
                checkpoint.getNodeId(),
                checkpoint.getNextNodeId(),
                checkpoint.getState(),
                sequenceGenerator.incrementAndGet(),
                Instant.now());
    }

    private static Checkpoint toCheckpoint(CheckpointEntity entity) {
        return Checkpoint.builder()
                .id(entity.getId())
                .state(entity.getState())
                .nodeId(entity.getNodeId())
                .nextNodeId(entity.getNextNodeId())
                .build();
    }
}
