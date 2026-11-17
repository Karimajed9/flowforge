ALTER TABLE workflows
    ADD COLUMN owner_subject VARCHAR(255),
    ADD COLUMN owner_issuer VARCHAR(512);

COMMENT ON COLUMN workflows.owner_subject IS
    'JWT sub recorded when the workflow is created. NULL is a workflow from before ownership and is not visible through user APIs.';

COMMENT ON COLUMN workflows.owner_issuer IS
    'Issuer of the JWT that created the workflow. Stored with owner_subject so a subject from a later issuer cannot collide.';

CREATE INDEX idx_workflows_owner
    ON workflows (owner_issuer, owner_subject);
