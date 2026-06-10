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
    signals                     JSONB       NOT NULL DEFAULT '[]'
);
