-- Phase 4: a roadmap step can name a real prerequisite — another step that must come first —
-- distinct from mere position in the list (order_index). This lets the adaptive resurfacing
-- engine reason about *why* a step is stuck (its prerequisite isn't done), not just that it is.
-- See CLAUDE.md Section 4.

ALTER TABLE entries
    ADD COLUMN depends_on BIGINT;

-- Self-referencing prerequisite. If the prerequisite step is deleted, dependents simply lose
-- the link (SET NULL) rather than cascading away — the dependent step itself still stands.
ALTER TABLE entries
    ADD CONSTRAINT fk_entries_depends_on
        FOREIGN KEY (depends_on) REFERENCES entries (id) ON DELETE SET NULL;

CREATE INDEX idx_entries_depends_on ON entries (depends_on);
