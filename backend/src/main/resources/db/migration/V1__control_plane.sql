-- ---------------------------------------------------------------------------
-- TestForge control plane.
--
-- Holds the durable record of what was requested, what was introspected and
-- what is currently leased. Deliberately does NOT hold job progress: that is
-- live view state for a run in flight, written on every phase transition and
-- polled a couple of times a second, and it has no business competing with the
-- audit trail for connections.
-- ---------------------------------------------------------------------------

CREATE TABLE schema_snapshot (
    id                UUID PRIMARY KEY,
    target_id         TEXT        NOT NULL,
    database_name     TEXT        NOT NULL,
    schema_name       TEXT        NOT NULL,
    fingerprint       TEXT        NOT NULL,
    table_count       INTEGER     NOT NULL,
    column_count      INTEGER     NOT NULL,
    foreign_key_count INTEGER     NOT NULL,
    payload           JSONB       NOT NULL,
    captured_at       TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A fingerprint is a content hash of the introspected structure. Re-introspecting
-- an unchanged schema must reuse the existing row rather than pile up duplicates.
CREATE UNIQUE INDEX ux_schema_snapshot_content
    ON schema_snapshot (target_id, schema_name, fingerprint);

CREATE INDEX ix_schema_snapshot_target
    ON schema_snapshot (target_id, captured_at DESC);


CREATE TABLE dataset (
    id                  UUID PRIMARY KEY,
    name                TEXT        NOT NULL,
    description         TEXT,
    requested_by        TEXT        NOT NULL,
    target_id           TEXT        NOT NULL,
    schema_name         TEXT        NOT NULL,
    snapshot_id         UUID        REFERENCES schema_snapshot (id) ON DELETE SET NULL,
    seed                BIGINT      NOT NULL,
    scale               INTEGER     NOT NULL,
    request             JSONB       NOT NULL,
    plan                JSONB,
    status              TEXT        NOT NULL,
    total_rows          BIGINT      NOT NULL DEFAULT 0,
    masked_columns      INTEGER     NOT NULL DEFAULT 0,
    duration_ms         BIGINT,
    error               TEXT,
    snapshot_uri        TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    CONSTRAINT ck_dataset_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

CREATE INDEX ix_dataset_requested_by ON dataset (requested_by, created_at DESC);
CREATE INDEX ix_dataset_status       ON dataset (status) WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX ix_dataset_target       ON dataset (target_id, created_at DESC);


CREATE TABLE lease (
    id             UUID PRIMARY KEY,
    dataset_id     UUID        NOT NULL REFERENCES dataset (id) ON DELETE CASCADE,
    database_name  TEXT        NOT NULL UNIQUE,
    jdbc_url       TEXT        NOT NULL,
    username       TEXT        NOT NULL,
    -- Passwords are generated per lease and shown exactly once, at issue time.
    -- Only the digest is retained, so a leaked control-plane dump cannot be used
    -- to connect to a live ephemeral database.
    password_digest TEXT       NOT NULL,
    holder         TEXT        NOT NULL,
    state          TEXT        NOT NULL,
    renewals       INTEGER     NOT NULL DEFAULT 0,
    issued_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    closed_at      TIMESTAMPTZ,
    CONSTRAINT ck_lease_state
        CHECK (state IN ('ACTIVE', 'EXPIRED', 'RELEASED', 'REVOKED', 'FAILED'))
);

-- The reaper's hot path: find every ACTIVE lease already past its expiry.
CREATE INDEX ix_lease_reaper ON lease (expires_at) WHERE state = 'ACTIVE';
CREATE INDEX ix_lease_holder ON lease (holder, issued_at DESC);
CREATE INDEX ix_lease_dataset ON lease (dataset_id);


CREATE TABLE snapshot_export (
    id           UUID PRIMARY KEY,
    dataset_id   UUID        NOT NULL REFERENCES dataset (id) ON DELETE CASCADE,
    uri          TEXT        NOT NULL,
    byte_size    BIGINT      NOT NULL,
    row_count    BIGINT      NOT NULL,
    table_count  INTEGER     NOT NULL,
    checksum     TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_snapshot_export_dataset ON snapshot_export (dataset_id, created_at DESC);
