ALTER TABLE workflows
    ADD COLUMN step_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE workflow_executions
    ADD COLUMN definition_frozen BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE execution_step_definitions (
    execution_id BIGINT NOT NULL
        REFERENCES workflow_executions(id) ON DELETE CASCADE,

    workflow_step_id BIGINT NOT NULL,

    name VARCHAR(150) NOT NULL,
    step_type VARCHAR(30) NOT NULL,
    position INTEGER NOT NULL,
    configuration JSONB NOT NULL,

    PRIMARY KEY (execution_id, workflow_step_id),

    CONSTRAINT uq_execution_step_definition_position
        UNIQUE (execution_id, position),

    CONSTRAINT chk_execution_step_definition_position
        CHECK (position > 0),

    CONSTRAINT chk_execution_step_definition_type
        CHECK (step_type IN ('VALIDATE_FIELDS', 'HTTP_REQUEST'))
);

INSERT INTO execution_step_definitions (
    execution_id,
    workflow_step_id,
    name,
    step_type,
    position,
    configuration
)
SELECT e.id,
       s.id,
       s.name,
       s.step_type,
       s.position,
       s.configuration
FROM workflow_executions e
JOIN workflow_steps s ON s.workflow_id = e.workflow_id;

UPDATE workflow_executions
SET definition_frozen = TRUE;

-- Checkpoints keep the step id they ran. The live row may be edited or
-- deleted later. The execution snapshot holds the name, type, and configuration.
DO $$
DECLARE
    constraint_name text;
BEGIN
    SELECT con.conname
    INTO constraint_name
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_attribute att
      ON att.attrelid = rel.oid
     AND att.attnum = ANY (con.conkey)
    WHERE rel.relname = 'execution_step_checkpoints'
      AND con.contype = 'f'
      AND att.attname = 'workflow_step_id';

    IF constraint_name IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE execution_step_checkpoints DROP CONSTRAINT %I',
            constraint_name);
    END IF;
END $$;
