CREATE TABLE gateway_requests (
    id TEXT PRIMARY KEY,
    received_at TEXT NOT NULL,
    completed_at TEXT,
    model TEXT NOT NULL,
    stream INTEGER NOT NULL,
    scheme_id TEXT,
    final_provider_id TEXT,
    final_outcome TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    total_latency_ms INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX gateway_requests_received ON gateway_requests(received_at DESC);
ALTER TABLE attempts ADD COLUMN attempt_index INTEGER NOT NULL DEFAULT 1;
ALTER TABLE attempts ADD COLUMN first_byte_at TEXT;
ALTER TABLE attempts ADD COLUMN request_bytes INTEGER NOT NULL DEFAULT 0;
ALTER TABLE attempts ADD COLUMN response_bytes INTEGER NOT NULL DEFAULT 0;
ALTER TABLE attempts ADD COLUMN error_code TEXT;
ALTER TABLE attempts ADD COLUMN failure_stage TEXT;
ALTER TABLE attempts ADD COLUMN skip_reason TEXT;
ALTER TABLE attempts ADD COLUMN upstream_request_id TEXT;
