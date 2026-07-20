-- Phase 12: a roadmap can be archived — dropped out of the main list into an Archive view
-- without being deleted. Modeled as a new status value on the existing flexible table
-- (CLAUDE.md Section 4), not a new column, so no data migration is needed.

ALTER TABLE entries DROP CONSTRAINT chk_entries_status;
ALTER TABLE entries ADD CONSTRAINT chk_entries_status
    CHECK (status IN ('captured', 'developing', 'in_motion', 'done', 'dropped', 'archived'));
