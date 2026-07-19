-- Phase 9: preferences the system infers from behaviour (session history, completion patterns,
-- reformulations) — proposed to the founder, confirmed/edited, then kept here as plain strings
-- and fed into future generation. A guess about the person, so it follows the confirm-before-
-- trust rule like the rest of the profile (CLAUDE.md).

ALTER TABLE learner_profile
    ADD COLUMN inferred_preferences JSONB;
