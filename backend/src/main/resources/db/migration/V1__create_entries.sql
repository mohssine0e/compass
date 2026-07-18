-- Compass core table. One flexible table for all entry types (see CLAUDE.md Section 4).
-- New entry types = a new `type` value + a payload shape in `content`, never a new table.

CREATE TABLE entries (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Real columns because the resurfacing engine queries on them directly.
    type                VARCHAR(32)  NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    significance        VARCHAR(16),           -- only meaningful for type = 'idea'
    parent_id           BIGINT,                -- e.g. a roadmap_step points to its roadmap
    order_index         INTEGER,               -- for ordered roadmap steps

    -- Type-specific payload (e.g. {text, notes, resources}); kept out of columns on purpose.
    content             JSONB        NOT NULL DEFAULT '{}'::jsonb,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_resurfaced_at  TIMESTAMPTZ,

    CONSTRAINT fk_entries_parent
        FOREIGN KEY (parent_id) REFERENCES entries (id) ON DELETE CASCADE,

    -- Documents the current domain; extending it is a one-line migration, not a new table.
    CONSTRAINT chk_entries_type
        CHECK (type IN ('idea', 'roadmap', 'roadmap_step')),
    CONSTRAINT chk_entries_status
        CHECK (status IN ('captured', 'developing', 'in_motion', 'done', 'dropped')),
    CONSTRAINT chk_entries_significance
        CHECK (significance IS NULL OR significance IN ('big', 'small'))
);

-- Indexes the resurfacing engine and roadmap/list views rely on.
CREATE INDEX idx_entries_type               ON entries (type);
CREATE INDEX idx_entries_status             ON entries (status);
CREATE INDEX idx_entries_parent_id          ON entries (parent_id);
CREATE INDEX idx_entries_last_resurfaced_at ON entries (last_resurfaced_at);
CREATE INDEX idx_entries_created_at         ON entries (created_at);
