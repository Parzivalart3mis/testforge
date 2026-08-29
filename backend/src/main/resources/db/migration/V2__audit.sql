-- ---------------------------------------------------------------------------
-- Audit trail.
--
-- A test data platform reads production-shaped schemas and hands out live
-- database credentials, so who asked for what — and which columns were masked
-- on the way — has to be answerable after the fact.
-- ---------------------------------------------------------------------------

CREATE TABLE audit_event (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor        TEXT        NOT NULL,
    action       TEXT        NOT NULL,
    subject_type TEXT        NOT NULL,
    subject_id   TEXT        NOT NULL,
    detail       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_audit_event_subject ON audit_event (subject_type, subject_id, occurred_at DESC);
CREATE INDEX ix_audit_event_actor   ON audit_event (actor, occurred_at DESC);
CREATE INDEX ix_audit_event_time    ON audit_event (occurred_at DESC);


-- Which columns a dataset masked, and how. Written once per dataset at plan
-- time so a compliance question ("was ssn ever seeded in the clear?") is a
-- single indexed query rather than a replay of the generation plan.
CREATE TABLE masking_record (
    dataset_id  UUID NOT NULL REFERENCES dataset (id) ON DELETE CASCADE,
    table_name  TEXT NOT NULL,
    column_name TEXT NOT NULL,
    data_class  TEXT NOT NULL,
    strategy    TEXT NOT NULL,
    rule_source TEXT NOT NULL,
    PRIMARY KEY (dataset_id, table_name, column_name)
);

CREATE INDEX ix_masking_record_class ON masking_record (data_class, strategy);
