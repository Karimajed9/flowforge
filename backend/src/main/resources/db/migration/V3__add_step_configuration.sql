ALTER TABLE workflow_steps
ADD COLUMN configuration JSONB NOT NULL
DEFAULT '{}'::jsonb;