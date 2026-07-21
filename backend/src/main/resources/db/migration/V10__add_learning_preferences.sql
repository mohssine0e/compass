-- Phase 15: structured learning-preference options (pace, theory-vs-practice, session length,
-- depth, example-vs-principle) — selectable, not just free text, so generation can size steps
-- and choose resources more deliberately than the free-text self-description alone allowed.

ALTER TABLE learner_profile ADD COLUMN learning_preferences jsonb;
