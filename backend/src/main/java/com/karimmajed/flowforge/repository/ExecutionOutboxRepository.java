package com.karimmajed.flowforge.repository;

import com.karimmajed.flowforge.entity.ExecutionOutboxEvent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExecutionOutboxRepository
        extends JpaRepository<ExecutionOutboxEvent, Long> {

    @Query(
        value = """
            SELECT *
            FROM execution_outbox
            WHERE published_at IS NULL
            ORDER BY id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """,
        nativeQuery = true
    )
    List<ExecutionOutboxEvent> lockPending(
        @Param("batchSize") int batchSize
    );
}