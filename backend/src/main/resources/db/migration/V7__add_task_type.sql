-- Phase 9: a "next step" answer from the resurfacing loop can become its own trackable thing —
-- a `task` entry, linked to the idea/roadmap it came from via parent_id. Still one flexible
-- table (CLAUDE.md Section 4): a new type value + a payload shape, not a new table.

ALTER TABLE entries DROP CONSTRAINT chk_entries_type;
ALTER TABLE entries ADD CONSTRAINT chk_entries_type
    CHECK (type IN ('idea', 'roadmap', 'roadmap_step', 'task'));
