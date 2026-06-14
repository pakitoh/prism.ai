-- prism.ai database schema.
--
-- Run once by the Postgres container's docker-entrypoint-initdb.d on first
-- initialization of an empty data directory (see docker-compose.yaml). Because it
-- runs only on a fresh data dir, schema changes require recreating the volume
-- (`docker compose down -v`); there is no on-startup migration.

CREATE EXTENSION IF NOT EXISTS vector;

-- Investigation aggregate: one row per investigation; signals kept as a JSONB array.
CREATE TABLE IF NOT EXISTS investigations (
    id                          UUID PRIMARY KEY,
    query                       TEXT        NOT NULL,
    service                     TEXT,
    window_from                 TIMESTAMPTZ,
    window_to                   TIMESTAMPTZ,
    source                      TEXT        NOT NULL,
    status                      TEXT        NOT NULL,
    finding_root_cause          TEXT,
    finding_evidence            TEXT,
    finding_recommended_action  TEXT,
    finding_confidence          TEXT,
    failure_reason              TEXT,
    signals                     JSONB       NOT NULL DEFAULT '[]',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Memory of past investigations. Embedding column has no fixed dimension, so the
-- table is not coupled to a particular embedding model. The set is small, so a
-- sequential scan is fine (no ANN index, which would require a fixed dimension).
CREATE TABLE IF NOT EXISTS investigation_embeddings (
    investigation_id    UUID PRIMARY KEY,
    query               TEXT   NOT NULL,
    root_cause          TEXT   NOT NULL,
    recommended_action  TEXT   NOT NULL,
    embedding           VECTOR NOT NULL
);
