-- RB-2.5: re-tier is a founder-triggered action, distinct from an AI provider event or a
-- system-side one — logged separately so /admin/events can tell "the founder did this" apart
-- from "the system/AI did this" at a glance.

ALTER TABLE system_events DROP CONSTRAINT chk_system_events_source;
ALTER TABLE system_events ADD CONSTRAINT chk_system_events_source
    CHECK (source IN ('ai_provider', 'system', 'founder'));
