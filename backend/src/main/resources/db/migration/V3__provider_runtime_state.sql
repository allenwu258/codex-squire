CREATE TABLE provider_runtime (
    provider_id TEXT PRIMARY KEY REFERENCES providers(id) ON DELETE CASCADE,
    health TEXT NOT NULL DEFAULT 'UNKNOWN',
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    last_success_at TEXT,
    last_failure_at TEXT,
    last_error_code TEXT,
    last_error_message TEXT,
    cooldown_until TEXT,
    average_latency_ms INTEGER NOT NULL DEFAULT 0,
    last_response_status INTEGER
);
