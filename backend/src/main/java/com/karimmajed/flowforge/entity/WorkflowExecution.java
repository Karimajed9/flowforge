package com.karimmajed.flowforge.entity;

import com.karimmajed.flowforge.enums.ExecutionStatus;

import jakarta.persistence.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Entity
@Table(name = "workflow_executions")
public class WorkflowExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id", nullable = false)
    private Workflow workflow;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExecutionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Column(
        name = "idempotency_key",
        nullable = false,
        length = 128
    )
    private String idempotencyKey;

    @Column(
        name = "created_at",
        nullable = false,
        updatable = false
    )
    private OffsetDateTime createdAt;

    protected WorkflowExecution() {
    }

    public WorkflowExecution(
            Workflow workflow,
            Map<String, Object> payload,
            String idempotencyKey) {

        this.workflow = workflow;
        this.payload = payload;
        this.idempotencyKey = idempotencyKey;
        this.status = ExecutionStatus.RECEIVED;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public Long getId() {
        return id;
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}