ALTER TABLE workflow_executions
ADD COLUMN recovery_attempts INTEGER NOT NULL DEFAULT 0,
ADD COLUMN last_recovery_requested_at TIMESTAMPTZ;

ALTER TABLE workflow_executions
ADD CONSTRAINT chk_execution_recovery_attempts
CHECK (recovery_attempts >= 0);

-- The original UNIQUE constraint allows only one
-- outbox event per execution, even after publication.
-- Recovery requires multiple historical events.

ALTER TABLE execution_outbox
DROP CONSTRAINT uq_execution_outbox;

-- But only one UNPUBLISHED event is allowed
-- per execution at a time.

CREATE UNIQUE INDEX
    uq_execution_outbox_pending_execution
ON execution_outbox (execution_id)
WHERE published_at IS NULL;