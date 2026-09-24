CREATE TABLE provider_capabilities (
    provider_id TEXT NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    model TEXT NOT NULL,
    supports_responses INTEGER NOT NULL,
    supports_sse INTEGER NOT NULL,
    usage_support TEXT NOT NULL,
    last_probe_at TEXT NOT NULL,
    last_error TEXT,
    PRIMARY KEY (provider_id, model)
);
