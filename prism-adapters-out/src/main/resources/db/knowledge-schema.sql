CREATE EXTENSION IF NOT EXISTS vector;

-- Embedding column has no fixed dimension, so the table is not coupled to a
-- particular embedding model. The set is small, so a sequential scan is fine
-- (no ANN index, which would require a fixed dimension).
CREATE TABLE IF NOT EXISTS investigation_embeddings (
    investigation_id    UUID PRIMARY KEY,
    query               TEXT   NOT NULL,
    root_cause          TEXT   NOT NULL,
    recommended_action  TEXT   NOT NULL,
    embedding           VECTOR NOT NULL
);
