-- RB-3: Canonical Topic Matching. Kept as its own table (not an `entries` row) — it's a
-- shared, growing reference catalog of topics the founder has generated roadmaps for before,
-- not a captured thing of the founder's own. No pgvector extension: cosine similarity is
-- computed in application code over the JSONB-stored float array, which is plenty at this
-- scale — revisit only if the topic count grows large enough to matter (RB-3.1).

CREATE TABLE canonical_topics (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    topic_id        VARCHAR(128) NOT NULL UNIQUE,  -- slug, e.g. "docker-fundamentals"
    canonical_name  VARCHAR(200) NOT NULL,
    aliases         JSONB        NOT NULL DEFAULT '[]',
    subtopics       JSONB        NOT NULL DEFAULT '[]',
    prerequisites   JSONB        NOT NULL DEFAULT '[]',
    related_topics  JSONB        NOT NULL DEFAULT '[]',
    embedding       JSONB        NOT NULL,          -- float array from EmbeddingService
    created_from    VARCHAR(200),                   -- the original goal text this came from
    -- Which real roadmap (entries.id) this canonical topic is "the" roadmap for — needed for
    -- RB-3.7's exact-match path ("open the existing roadmap directly"). Nullable: a topic can
    -- outlive the roadmap it was created from (deleted/re-tiered to a task), in which case an
    -- exact match falls back to the NEW path rather than opening something that's gone.
    roadmap_entry_id BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    usage_count     INTEGER      NOT NULL DEFAULT 1,
    last_used_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_canonical_topics_last_used ON canonical_topics (last_used_at DESC);
