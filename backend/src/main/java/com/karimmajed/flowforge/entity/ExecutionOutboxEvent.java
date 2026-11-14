package com.karimmajed.flowforge.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "execution_outbox")
public class ExecutionOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false)
    private WorkflowExecution execution;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    protected ExecutionOutboxEvent() {
    }

    public ExecutionOutboxEvent(WorkflowExecution execution) {
        this.execution = execution;
    }

    public Long getId() {
        return id;
    }

    public WorkflowExecution getExecution() {
        return execution;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public void markPublished() {
        this.publishedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}