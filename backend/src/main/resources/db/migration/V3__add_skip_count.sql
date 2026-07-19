-- Phase 4: count how many times a resurfaced item was skipped without action, so the
-- self-talk voice can tell one skip apart from a repeated pattern of avoidance and ask about
-- it honestly ("is this the wrong next step, or are you avoiding it?"). See CLAUDE.md Section 2.
-- Any real engagement with the item resets this to 0 (application logic), so it measures a
-- current streak of avoidance, not lifetime skips.

ALTER TABLE entries
    ADD COLUMN skip_count INTEGER NOT NULL DEFAULT 0;
