-- Phase 7.5: which learning-resource formats the founder prefers or avoids, so resource
-- discovery can filter suggestions (e.g. never suggest videos if they avoid video). Lives on
-- the learner profile as its own small JSONB field: {"avoid": ["video"], "prefer": ["written"]}.

ALTER TABLE learner_profile
    ADD COLUMN format_preferences JSONB;
