-- RES-1: the shared resource enrichment cache. Kept as its own table (not an `entries` row) —
-- like canonical_topics, it's a shared, growing reference cache the system builds up, not
-- something the founder personally captured (CLAUDE.md Section 4). Genuinely shared across
-- roadmaps: the same resource_url is never re-processed twice even when a different roadmap
-- references it for a different step, which is what the (resource_url, topic_key) cache key
-- is for.
--
-- No TTL, no expiry, unlike the search-result cache in SearchGroundingService — an entry here
-- is reused forever once created.

CREATE TABLE resource_enrichments (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    resource_url        TEXT         NOT NULL,
    -- Short, stable string identifying the step/topic context this enrichment was generated
    -- for — a slugified version of the step's title, not a full free-text match. The same
    -- resource_url can carry a different focus pointer per topic_key: a general Rust reference
    -- page linked from an "ownership" step needs a different "what to focus on" than the same
    -- page linked from a "concurrency" step.
    topic_key           VARCHAR(200) NOT NULL,
    kind                VARCHAR(16)  NOT NULL,
    -- Written-resource fields.
    focus_pointer        TEXT,
    -- Video-resource fields (nullable: unset for kind='written', and segment_* stay null for a
    -- video with no transcript available — see description_fallback below).
    segment_start        INTEGER,
    segment_end          INTEGER,
    segment_description   TEXT,
    source               VARCHAR(32)  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_resource_enrichments_kind
        CHECK (kind IN ('written', 'video')),
    CONSTRAINT chk_resource_enrichments_source
        CHECK (source IN ('exa_highlight', 'fetch_fallback', 'transcript', 'description_fallback'))
);

-- The actual cache key: a lookup on this pair before doing any enrichment work is the
-- cache-hit check (RES-3/RES-4's lazy trigger reads this first, every time).
CREATE UNIQUE INDEX idx_resource_enrichments_url_topic
    ON resource_enrichments (resource_url, topic_key);
