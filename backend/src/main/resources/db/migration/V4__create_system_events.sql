-- Phase 5: a lightweight, human-scannable record of what's going wrong (AI fallbacks,
-- provider errors, system-side failures) — for noticing patterns early, not for debugging.
-- Kept separate from `entries` because it's operational data about the system itself, not
-- something the founder captured (CLAUDE.md Section 4). Full stack traces belong in normal
-- server logs; `message` here is hard-capped to a sentence on purpose.

CREATE TABLE system_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    source      VARCHAR(32)  NOT NULL,   -- who: an AI provider, or the system itself
    category    VARCHAR(64)  NOT NULL,   -- what kind: timeout, provider_error, parse_failure, db_error, …
    message     VARCHAR(500) NOT NULL,   -- one short sentence (also capped in the app layer)
    context     JSONB,                   -- small, optional — e.g. {"entryId": 5}
    severity    VARCHAR(16)  NOT NULL,

    CONSTRAINT chk_system_events_source
        CHECK (source IN ('ai_provider', 'system')),
    CONSTRAINT chk_system_events_severity
        CHECK (severity IN ('info', 'warning', 'error'))
);

-- The admin view lists most-recent-first and filters by source/severity.
CREATE INDEX idx_system_events_occurred_at ON system_events (occurred_at DESC);
CREATE INDEX idx_system_events_source      ON system_events (source);
CREATE INDEX idx_system_events_severity    ON system_events (severity);
