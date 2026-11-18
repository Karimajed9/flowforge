-- Reorder and delete assign final positions in one transaction. A non-deferrable
-- unique constraint is checked row by row, so those updates used a temporary
-- position derived from the step id. That temporary value overflows INTEGER
-- when the id is near the integer limit, and it can also collide with a real
-- position. Defer the uniqueness check until the transaction commits instead.
ALTER TABLE workflow_steps
    DROP CONSTRAINT uq_workflow_step_position;

ALTER TABLE workflow_steps
    ADD CONSTRAINT uq_workflow_step_position
        UNIQUE (workflow_id, position)
        DEFERRABLE INITIALLY IMMEDIATE;
