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
- [x] Big/small tap on capture (sets `significance` field) — for `idea` type only
- [x] `roadmap` and `roadmap_step` entry types: create a roadmap with an ordered list of
  steps (founder writes steps manually for v1 — AI-assisted roadmap generation is a later
  nice-to-have, not required for Phase 1)
- [x] Roadmap view: shows steps in order, current position, what's done, what's left
- [x] `PATCH /entries/{id}` — mark a roadmap step (or idea) as done, self-reported
- [x] AI voice service: single method that takes an entry and returns a short
  self-talk-voice acknowledgment (see CLAUDE.md Section 2 for tone rules) — wire this into
  the capture flow and into marking a roadmap step done. Implemented provider-agnostically
  (OpenAI-compatible endpoint, Gemini primary / Groq backup, best-effort with plain
  "Held." fallback on outage — see backend/.env.example).
- [x] Plain list view of all entries/roadmaps — no charts, no analytics, just visibility
- [x] **Push + tag `phase-1-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 2 — Resurfacing

Goal: the app proactively surfaces one stalled thing before letting you capture something
new, and asks an honest question about it — with multiple ways to answer.

- [x] Resurfacing service: generic query for "unresolved big ideas or stalled roadmap
  steps, not touched in N days" (query by `type`, `status`, `last_resurfaced_at` — should
  not be hardcoded to `idea` only, per CLAUDE.md Section 4)
- [x] On app open: if something qualifies for resurfacing, show it *before* the capture
  screen, with the honest question generated in self-talk voice
- [x] Response modes for the honest question: free text, voice, and a short list of
  default options ("still relevant" / "stuck" / "lost interest" / "something else")
- [x] Skip/snooze option, clearly labeled as a real state (not silently repeated every time)
- [x] `last_resurfaced_at` updates correctly so the same item isn't resurfaced every
  single open
- [x] **Push + tag `phase-2-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 3 — Roadmap editing

Goal: fix the current roadmap view's biggest gap — there's no way to correct a mistake.
Small, low-risk, ship fast before anything AI-facing gets added on top.

- [x] `PATCH /entries/{id}` supports un-marking a step as done (undo), not just marking done
- [x] Edit a step's content (title/notes) after creation
- [x] Reorder steps within a roadmap
- [ ] Insert a new step mid-roadmap (not just appended at the end)
- [ ] Delete a step
- [ ] **Push + tag `phase-3-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 4 — Roadmap intelligence

Goal: the AI can help draft a roadmap instead of the founder writing every step by hand,
and stalled steps can get *restructured*, not just asked about. This directly extends the
resurfacing engine from Phase 2 — it's the same "notice something's stalled" trigger, but
the response can now be "let's change the plan" instead of only "here's a question about
it." This phase is more AI-facing and higher-risk to tone/quality than Phase 3 — review
the actual generated output carefully before trusting it.

**AI-generated roadmap from a goal**
- [ ] `POST /roadmaps/generate` (or similar): given a goal in plain text, the AI asks 1–2
  clarifying questions (time available per week, prior experience) before proposing an
  ordered step breakdown — not a single-shot generation, per CLAUDE.md tone/quality bar
- [ ] Proposed roadmap is editable before the user accepts it — AI drafts, user owns the
  final version
- [ ] `roadmap_step` gains an optional `depends_on` field (references another step's id)
  so steps can express real prerequisites, not just position-in-list order. Needed for
  the adaptive restructuring below to reason about *why* something's stuck, not just
  *that* it's stuck.

**Adaptive resurfacing (extends the Phase 2 resurfacing engine)**
- [ ] When a roadmap step has been stalled long enough to qualify for resurfacing, the
  self-talk-voice question can now offer restructuring as a response option, not just
  "still relevant / stuck / lost interest" — e.g. "break this into smaller steps?" or
  "reorder — tackle something else first?"
- [ ] If the user picks a restructuring option, the AI proposes a specific edit (e.g. split
  one step into two, or suggest a prerequisite step) — user still approves before it's
  applied; never auto-edit silently
- [ ] Track repeated skips on the same item distinctly from a single skip (see CLAUDE.md
  Section 2 — this is what lets the self-talk voice eventually ask "is this the wrong next
  step, or are you avoiding it?" honestly, instead of nagging on every skip identically)
- [ ] **Push + tag `phase-4-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 5 — Verification

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
- [ ] **Spaced retrieval on already-completed steps**: reuse the question-generation
  service to occasionally re-check a step that's already marked done, spaced out over
  increasing intervals (e.g. a few days, then weeks) — this is the single most
  evidence-backed learning technique (spaced repetition) and it's nearly free here since
  it's the same engine firing on a different trigger (completed + due for recheck, via
  the resurfacing service) rather than a new system
- [ ] **Push + tag `phase-5-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 6 — Follow-through

Goal: standalone captured ideas can turn into real tracked action, not just sit as text.

- [ ] "Next step" answers from the resurfacing loop become their own trackable
  entry (linked via `parent_id`, type `task` or similar)
- [ ] Progress states for standalone ideas: captured → developing → in motion →
  done/dropped, reflected in the list/roadmap views
- [ ] Per-topic personalization: when generating the default-options list for the honest
  question, check for real history on that specific thread first (per CLAUDE.md — per-topic,
  not global) and use it if present; otherwise fall back to the generic default list. Be
  explicit (in the self-talk voice) about which mode it's in.
- [ ] Daily/session focus view: a deliberately narrow view showing one resurfaced item
  (if any) plus the current step of one active roadmap — not a full list of everything.
  Optional alternate landing view, not a replacement for the plain list.
- [ ] Staleness as a passive visual cue on the roadmap list (e.g. "6 days since touched"
  next to a step), separate from the active resurfacing prompt — lets the founder notice
  drift without waiting for the system to interrupt them
- [ ] **Push + tag `phase-6-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 7 — Depth

Goal: the system starts noticing patterns across sessions, not just within one thread.

- [ ] Idea/roadmap threading: detect and surface recurring themes or repeatedly-stalled
  steps across separate entries
- [ ] Weekly self-talk-voice review: a summary of where things stand across all active
  roadmaps and ideas, generated once there's enough history to be meaningful
- [ ] **Push + tag `phase-7-complete`.**

---

## Explicitly not planned

Do not add these unless the founder asks directly — they were considered and cut during
design (see CLAUDE.md Section 7):
- Mood tracking
- Journaling prompts / worksheets unrelated to the core loop
- Any AI-facing copy that doesn't follow the self-talk-voice tone rules in CLAUDE.md
- Streaks / "don't break the chain" gamification — optimizes for not-missing-a-day rather
  than real progress, and tends to produce guilt-driven engagement, which conflicts with
  the self-talk-voice honesty principle. If any consistency signal is added later, frame
  it as total sessions/time invested, not an unbroken streak.
- Branching/non-linear roadmap diagrams — current data model is intentionally linear
  (`order_index` + optional `depends_on` from Phase 4). Revisit only if a real roadmap
  needs actual branching or parallel tracks, not just for visual polish.