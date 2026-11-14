UPDATE workflow_executions
SET idempotency_key = 'legacy~' || id
WHERE idempotency_key IS NULL;

ALTER TABLE workflow_executions
ALTER COLUMN idempotency_key SET NOT NULL;