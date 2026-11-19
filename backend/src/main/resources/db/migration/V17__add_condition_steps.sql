ALTER TABLE workflow_steps
    DROP CONSTRAINT chk_step_type;

ALTER TABLE workflow_steps
    ADD CONSTRAINT chk_step_type
        CHECK (step_type IN ('VALIDATE_FIELDS', 'HTTP_REQUEST', 'CONDITION'));

ALTER TABLE execution_step_definitions
    DROP CONSTRAINT chk_execution_step_definition_type;

ALTER TABLE execution_step_definitions
    ADD CONSTRAINT chk_execution_step_definition_type
        CHECK (step_type IN ('VALIDATE_FIELDS', 'HTTP_REQUEST', 'CONDITION'));

ALTER TABLE execution_step_checkpoints
    DROP CONSTRAINT chk_execution_step_checkpoint_status;

ALTER TABLE execution_step_checkpoints
    ADD CONSTRAINT chk_execution_step_checkpoint_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED'));

ALTER TABLE execution_step_checkpoints
    ADD COLUMN branch_taken BOOLEAN NULL;
