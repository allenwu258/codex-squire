CREATE TABLE quota_rules (
    id TEXT PRIMARY KEY,
    provider_id TEXT NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    metric TEXT NOT NULL CHECK (metric IN ('REQUEST_COUNT','INPUT_TOKENS','OUTPUT_TOKENS','TOTAL_TOKENS','CONCURRENT_REQUESTS')),
    window_type TEXT NOT NULL CHECK (window_type IN ('MINUTE','HOUR','DAY','WEEK','MONTH')),
    window_size INTEGER NOT NULL CHECK (window_size > 0),
    timezone TEXT NOT NULL,
    threshold INTEGER NOT NULL CHECK (threshold > 0),
    action TEXT NOT NULL CHECK (action IN ('SKIP_PROVIDER','REJECT_REQUEST','ENTER_COOLDOWN','DEGRADE')),
    enabled INTEGER NOT NULL CHECK (enabled IN (0,1)),
    priority INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX quota_rules_provider ON quota_rules(provider_id, enabled, priority);
