-- Compass demo seed data.
--
-- Populates the entries table with a realistic mix of ideas and roadmaps so the app has
-- something to show. Run against a local dev database:
--
--     psql -d compass -f backend/seed.sql
--
-- Dev/demo only: this WIPES the entries table first, so do not run it on data you care
-- about. created_at values are staggered into the past so the "newest first" list and
-- (later) the resurfacing engine have realistic ages to work with.

TRUNCATE entries RESTART IDENTITY CASCADE;

-- --- Standalone ideas (varied significance, status, and age) ---
INSERT INTO entries (type, status, significance, content, created_at, updated_at) VALUES
  ('idea', 'captured',   'big',   '{"text":"Build Compass into something I actually reach for every day"}'::jsonb, now() - interval '9 days', now() - interval '9 days'),
  ('idea', 'captured',   'big',   '{"text":"Rethink resurfacing so it feels honest, not naggy"}'::jsonb,          now() - interval '6 days', now() - interval '6 days'),
  ('idea', 'developing', 'big',   '{"text":"A weekly review that reads like my own voice"}'::jsonb,                now() - interval '5 days', now() - interval '2 days'),
  ('idea', 'captured',   'small', '{"text":"Global shortcut to capture from anywhere"}'::jsonb,                    now() - interval '3 days', now() - interval '3 days'),
  ('idea', 'captured',   'small', '{"text":"Export everything captured as plain markdown"}'::jsonb,                now() - interval '2 days', now() - interval '2 days'),
  ('idea', 'dropped',    'small', '{"text":"Add a landing page before capture"}'::jsonb,                          now() - interval '8 days', now() - interval '7 days'),
  ('idea', 'captured',   null,    '{"text":"Voice notes on a walk should turn into roadmap steps"}'::jsonb,        now() - interval '1 day',  now() - interval '1 day');

-- --- Roadmap 1: Learn Rust properly (in motion; first two steps done) ---
WITH r AS (
  INSERT INTO entries (type, status, content, created_at, updated_at)
  VALUES ('roadmap', 'in_motion', '{"title":"Learn Rust properly","notes":"evenings only"}'::jsonb,
          now() - interval '7 days', now() - interval '1 day')
  RETURNING id
)
INSERT INTO entries (type, status, parent_id, order_index, content, created_at, updated_at)
SELECT 'roadmap_step', s.status, r.id, s.ord, s.content, now() - interval '7 days', s.updated_at
FROM r, (VALUES
  ('done',     0, '{"text":"Ownership & borrowing"}'::jsonb,          now() - interval '5 days'),
  ('done',     1, '{"text":"Lifetimes"}'::jsonb,                      now() - interval '3 days'),
  ('captured', 2, '{"text":"Traits & generics"}'::jsonb,             now() - interval '7 days'),
  ('captured', 3, '{"text":"Error handling & the ? operator"}'::jsonb, now() - interval '7 days'),
  ('captured', 4, '{"text":"Async & the Tokio runtime"}'::jsonb,      now() - interval '7 days')
) AS s(status, ord, content, updated_at);

-- --- Roadmap 2: Ship Compass Phase 1 (further along) ---
WITH r AS (
  INSERT INTO entries (type, status, content, created_at, updated_at)
  VALUES ('roadmap', 'in_motion', '{"title":"Ship Compass Phase 1"}'::jsonb,
          now() - interval '10 days', now() - interval '1 day')
  RETURNING id
)
INSERT INTO entries (type, status, parent_id, order_index, content, created_at, updated_at)
SELECT 'roadmap_step', s.status, r.id, s.ord, s.content, now() - interval '10 days', s.updated_at
FROM r, (VALUES
  ('done',     0, '{"text":"Capture screen with voice"}'::jsonb,          now() - interval '8 days'),
  ('done',     1, '{"text":"Roadmaps with self-marked progress"}'::jsonb, now() - interval '4 days'),
  ('captured', 2, '{"text":"Use it for real for a week"}'::jsonb,         now() - interval '10 days')
) AS s(status, ord, content, updated_at);
