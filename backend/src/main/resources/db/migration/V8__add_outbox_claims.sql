ALTER TABLE execution_outbox
ADD COLUMN claim_token VARCHAR(64);

ALTER TABLE execution_outbox
ADD COLUMN claimed_until TIMESTAMPTZ;

CREATE INDEX idx_execution_outbox_claims
ON execution_outbox(id)
WHERE published_at IS NULL;