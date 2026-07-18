# TASKS.md — Compass Build Plan

Work top to bottom. Do not skip ahead. See CLAUDE.md for the "why" behind this ordering
and the product philosophy each task should honor.

**After every task**: commit with a clear message, check the box below.
**After every phase**: push to GitHub, tag the commit (`phase-N-complete`), then STOP
and wait for the founder to review/use it before starting the next phase.

---

## Phase 1 — Core loop + roadmap skeleton

Goal: a working app where you can capture a thought or start a roadmap, see where you
are, and mark things done yourself. No AI verification yet. No resurfacing yet.

- [x] Project scaffolding: Spring Boot app, Postgres connection, Flyway migrations set up
- [x] `entries` table migration (see CLAUDE.md Section 4 for schema)
- [x] `POST /entries` — create an entry (type: `idea`), plain text content
- [x] `GET /entries` — list entries, newest first
- [x] Frontend (PWA): capture screen as the app's home/landing view — single text box,
  submit button, nothing else on screen by default
- [x] Voice input on the capture screen (Web Speech API), toggle between typing/speaking
- [ ] Big/small tap on capture (sets `significance` field) — for `idea` type only
- [ ] `roadmap` and `roadmap_step` entry types: create a roadmap with an ordered list of
  steps (founder writes steps manually for v1 — AI-assisted roadmap generation is a later
  nice-to-have, not required for Phase 1)
- [ ] Roadmap view: shows steps in order, current position, what's done, what's left
- [ ] `PATCH /entries/{id}` — mark a roadmap step (or idea) as done, self-reported
- [ ] AI voice service: single method that takes an entry and returns a short
  self-talk-voice acknowledgment (see CLAUDE.md Section 2 for tone rules) — wire this into
  the capture flow and into marking a roadmap step done
- [ ] Plain list view of all entries/roadmaps — no charts, no analytics, just visibility
- [ ] **Push + tag `phase-1-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 2 — Resurfacing

Goal: the app proactively surfaces one stalled thing before letting you capture something
new, and asks an honest question about it — with multiple ways to answer.

- [ ] Resurfacing service: generic query for "unresolved big ideas or stalled roadmap
  steps, not touched in N days" (query by `type`, `status`, `last_resurfaced_at` — should
  not be hardcoded to `idea` only, per CLAUDE.md Section 4)
- [ ] On app open: if something qualifies for resurfacing, show it *before* the capture
  screen, with the honest question generated in self-talk voice
- [ ] Response modes for the honest question: free text, voice, and a short list of
  default options ("still relevant" / "stuck" / "lost interest" / "something else")
- [ ] Skip/snooze option, clearly labeled as a real state (not silently repeated every time)
- [ ] `last_resurfaced_at` updates correctly so the same item isn't resurfaced every
  single open
- [ ] **Push + tag `phase-2-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 3 — Verification

Goal: roadmap steps can optionally require a real check of understanding before counting
as done, instead of pure self-reporting.

- [ ] AI question-generation service: given a roadmap step's content, generate a fair,
  non-trivial check (question or short task)
- [ ] AI evaluation service: given the user's answer, judge whether it demonstrates real
  understanding — return a specific gap, not just pass/fail, when it doesn't
- [ ] Wire verification into "mark step done" as an optional gate (light-check vs. full
  check — not every step needs the same rigor; let this be configurable per step or per
  roadmap)
- [ ] Handle wrong/shaky answers honestly in self-talk voice — point at the specific gap,
  don't just block silently
- [ ] **Push + tag `phase-3-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 4 — Follow-through

Goal: standalone captured ideas can turn into real tracked action, not just sit as text.

- [ ] "Next step" answers from the resurfacing loop become their own trackable
  entry (linked via `parent_id`, type `task` or similar)
- [ ] Progress states for standalone ideas: captured → developing → in motion →
  done/dropped, reflected in the list/roadmap views
- [ ] Per-topic personalization: when generating the default-options list for the honest
  question, check for real history on that specific thread first (per CLAUDE.md — per-topic,
  not global) and use it if present; otherwise fall back to the generic default list. Be
  explicit (in the self-talk voice) about which mode it's in.
- [ ] **Push + tag `phase-4-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 5 — Depth

Goal: the system starts noticing patterns across sessions, not just within one thread.

- [ ] Idea/roadmap threading: detect and surface recurring themes or repeatedly-stalled
  steps across separate entries
- [ ] Weekly self-talk-voice review: a summary of where things stand across all active
  roadmaps and ideas, generated once there's enough history to be meaningful
- [ ] **Push + tag `phase-5-complete`.**

---

## Explicitly not planned

Do not add these unless the founder asks directly — they were considered and cut during
design (see CLAUDE.md Section 7):
- Mood tracking
- Journaling prompts / worksheets unrelated to the core loop
- Any AI-facing copy that doesn't follow the self-talk-voice tone rules in CLAUDE.md