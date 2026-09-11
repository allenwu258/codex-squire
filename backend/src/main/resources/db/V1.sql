CREATE TABLE providers (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    base_url TEXT NOT NULL,
    encrypted_key TEXT NOT NULL,
    enabled INTEGER NOT NULL,
    daily_request_limit INTEGER NOT NULL CHECK (daily_request_limit >= 0),
    daily_token_limit INTEGER NOT NULL CHECK (daily_token_limit >= 0),
    max_concurrency INTEGER NOT NULL CHECK (max_concurrency > 0),
    token_reservation INTEGER NOT NULL CHECK (token_reservation > 0),
    created_at TEXT NOT NULL
);
CREATE TABLE schemes (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    strategy TEXT NOT NULL CHECK (strategy IN ('ORDERED','BALANCED')),
    active INTEGER NOT NULL DEFAULT 0 CHECK (active IN (0,1))
);
CREATE UNIQUE INDEX one_active_scheme ON schemes(active) WHERE active = 1;
CREATE TABLE scheme_members (
    scheme_id TEXT NOT NULL REFERENCES schemes(id) ON DELETE CASCADE,
    provider_id TEXT NOT NULL REFERENCES providers(id) ON DELETE RESTRICT,
    position INTEGER NOT NULL,
    weight INTEGER NOT NULL CHECK (weight BETWEEN 1 AND 100),
    PRIMARY KEY (scheme_id, provider_id),
    UNIQUE (scheme_id, position)
);
CREATE TABLE attempts (
    id TEXT PRIMARY KEY,
    request_id TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    provider_name TEXT NOT NULL,
    scheme_id TEXT NOT NULL,
    model TEXT NOT NULL,
    started_at TEXT NOT NULL,
    day TEXT NOT NULL,
    ended_at TEXT,
    status TEXT NOT NULL,
    http_status INTEGER,
    reserved_tokens INTEGER NOT NULL,
    charged_tokens INTEGER NOT NULL,
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    cached_tokens INTEGER NOT NULL DEFAULT 0,
    usage_state TEXT NOT NULL,
    latency_ms INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX attempts_provider_day ON attempts(provider_id, day);
CREATE INDEX attempts_inflight ON attempts(provider_id) WHERE status = 'IN_FLIGHT';
CREATE INDEX attempts_started ON attempts(started_at DESC);
CREATE INDEX attempts_request ON attempts(request_id);
PRAGMA user_version = 1;
