package io.github.manevpe.agentic.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface CheckpointJpaRepository extends JpaRepository<CheckpointEntity, String> {

    List<CheckpointEntity> findByThreadIdOrderBySequenceDesc(String threadId);

    void deleteByThreadId(String threadId);
}
