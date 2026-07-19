-- Phase 6: the learner profile — what the founder already knows — so Phase 7 generation
-- doesn't re-teach it. Kept as its own singleton table, not an `entries` row, because it's one
-- current profile edited in place, not a growing collection of captured things (CLAUDE.md §4).
--
-- Every AI interpretation here (resume extraction, self-description traits) is a guess shown
-- back for confirmation: generation must not read this profile until `confirmed_at` is set.

CREATE TABLE learner_profile (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- List of {name, confidence} pairs; confidence is optional (just_started/comfortable/solid).
    skills            JSONB        NOT NULL DEFAULT '[]'::jsonb,

    -- Structured data pulled from a resume ({skills, experience, education}). The raw uploaded
    -- file itself is never stored — only this extracted structure (see resume retention task).
    resume_extracted  JSONB,

    -- The founder's free-text "how I like to learn" plus the AI-interpreted traits ({raw, traits}).
    self_description  JSONB,

    -- Null until the founder has reviewed and approved the profile at least once. Generation
    -- (Phase 7) must treat a null here as "no usable profile yet".
    confirmed_at      TIMESTAMPTZ,

    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
