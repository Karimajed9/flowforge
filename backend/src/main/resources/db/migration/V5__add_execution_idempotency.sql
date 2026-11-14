ALTER TABLE workflow_executions
ADD COLUMN idempotency_key VARCHAR(128);

ALTER TABLE workflow_executions
ADD CONSTRAINT uq_execution_idempotency
UNIQUE (workflow_id, idempotency_key);