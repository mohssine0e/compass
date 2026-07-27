-- V3-3.2: two queries filter on JSONB fields inside `content` and were sequential scans with a
-- per-row JSONB extraction. Both are partial indexes — they cover only the rows the predicate
-- actually matches, so they stay tiny regardless of how many entries exist.

-- findNextRecheckCandidate (Phase 8 spaced recheck) — runs on a schedule rather than on a user
-- action, so it's worth not scanning the whole table for it.
--
-- Note this indexes the *candidate set*, not the timestamp. The obvious version — indexing
-- ((content ->> 'nextRecheckAt')::timestamptz) so the ORDER BY comes off the index too — is
-- rejected by Postgres: text -> timestamptz depends on the session TimeZone setting, making it
-- STABLE rather than IMMUTABLE, and index expressions must be IMMUTABLE. Sorting the matched
-- rows in memory is fine here: the set is only ever "done steps that have a recheck scheduled".
CREATE INDEX idx_entries_next_recheck
    ON entries (id)
    WHERE type = 'roadmap_step'
      AND status = 'done'
      AND content ->> 'nextRecheckAt' IS NOT NULL;

-- findModuleIdsWithSkeletonSteps — the background sweep looking for modules whose expansion fell
-- back to titles-only (Phase 19). Almost always matches nothing, which is exactly the case a
-- partial index makes free. `->>` and `=` are both immutable, so this one indexes normally.
CREATE INDEX idx_entries_skeleton_only
    ON entries (parent_id)
    WHERE type = 'roadmap_step'
      AND content ->> 'skeletonOnly' = 'true';
