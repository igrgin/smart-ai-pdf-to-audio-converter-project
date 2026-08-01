SET search_path TO workflow, admission, public;

ALTER TABLE workflow.inspection_work
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0);
