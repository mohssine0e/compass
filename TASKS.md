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
- [x] Insert a new step mid-roadmap (not just appended at the end)
- [x] Delete a step
- [x] **Push + tag `phase-3-complete`. Stop. Let the founder use this for real before
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
- [x] `POST /roadmaps/generate` (or similar): given a goal in plain text, the AI asks 1–2
  clarifying questions (time available per week, prior experience) before proposing an
  ordered step breakdown — not a single-shot generation, per CLAUDE.md tone/quality bar
- [x] Proposed roadmap is editable before the user accepts it — AI drafts, user owns the
  final version
- [x] `roadmap_step` gains an optional `depends_on` field (references another step's id)
  so steps can express real prerequisites, not just position-in-list order. Needed for
  the adaptive restructuring below to reason about *why* something's stuck, not just
  *that* it's stuck.

**Adaptive resurfacing (extends the Phase 2 resurfacing engine)**
- [x] When a roadmap step has been stalled long enough to qualify for resurfacing, the
  self-talk-voice question can now offer restructuring as a response option, not just
  "still relevant / stuck / lost interest" — e.g. "break this into smaller steps?" or
  "reorder — tackle something else first?"
- [x] If the user picks a restructuring option, the AI proposes a specific edit (e.g. split
  one step into two, or suggest a prerequisite step) — user still approves before it's
  applied; never auto-edit silently
- [x] Track repeated skips on the same item distinctly from a single skip (see CLAUDE.md
  Section 2 — this is what lets the self-talk voice eventually ask "is this the wrong next
  step, or are you avoiding it?" honestly, instead of nagging on every skip identically)
- [x] **Push + tag `phase-4-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 5 — Observability & error reporting

Goal: a small, honest window into what's going wrong, before the next phases add more
external dependencies (search grounding, file parsing) that can fail in new ways. Small
and low-risk — build this before piling on more AI-facing complexity, same reasoning as
Phase 3 before Phase 4.

Keep every log entry **brief** — a short message, not a full stack trace. Full technical
detail belongs in normal server logs (stdout/file, whatever Spring Boot already does);
this is a lightweight, human-scannable record for noticing patterns early, not a debugger.

- [x] `system_events` table: `id`, `occurred_at`, `source` (`ai_provider` | `system`),
  `category` (e.g. `timeout`, `provider_error`, `parse_failure`, `db_error`), `message`
  (short text, hard-capped length — a sentence, not a paragraph), `context` (nullable,
  small JSONB — e.g. which entry/roadmap it relates to), `severity` (`info` | `warning` |
  `error`)
- [x] Every AI call site (voice acknowledgment, roadmap generation, resurfacing questions)
  logs a brief event on failure or fallback — e.g. "Gemini timed out, Groq backup used" or
  "both AI providers failed, fell back to plain acknowledgment" — not the raw exception
- [x] Key system-side failures (DB errors, failed migrations at runtime, unexpected nulls
  in critical paths) also log a brief event, same table, `source: system`
- [x] `GET /admin/events` (or similar) — simple list, most recent first, filterable by
  `source`/`severity`
- [x] Minimal admin view in the frontend: a plain list of recent events, nothing fancier
  than the existing plain-list philosophy elsewhere in the app
- [ ] Retention: cap how much accumulates (e.g. keep the last N events or auto-prune
  anything older than a set window) so this stays a lightweight recent-signal view, not an
  ever-growing log dump
- [ ] **Push + tag `phase-5-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 6 — Learner profile

Goal: a place to tell the system what you already know, so future roadmap generation
(Phase 7) doesn't waste your time re-teaching things you've already learned. Every AI
interpretation of what you provide gets shown back to you for confirmation or correction
before it's trusted — same propose→approve→apply trust pattern as Phase 4, applied to your
own profile instead of a roadmap edit.

- [ ] Profile data model: a `learner_profile` record (skills list with optional confidence
  level, free-text self-description, structured data extracted from an uploaded resume)
- [ ] Manual skills entry: add/remove skill tags, each with an optional confidence level
  (e.g. "just started" / "comfortable" / "solid") — confidence matters here specifically
  because "I know Python" means very different things to different people, which is the
  exact ambiguity that caused the re-teaching problem in the first place
- [ ] Resume/CV upload: accept PDF/DOCX, extract raw text locally (e.g. Apache PDFBox for
  PDF, Apache POI for DOCX — no external API needed for extraction itself)
- [ ] AI extraction pass: send the extracted resume text to the existing AI provider,
  prompted to pull out structured skills/experience/education — this reuses the existing
  Gemini/Groq setup, no new provider needed for this step
- [ ] Free-text self-description field ("how you like to learn/think") — AI-interpreted
  into a few structured traits (e.g. "prefers concrete examples," "learns fast, skips
  fundamentals if bored"), not stored as a raw guess
- [ ] **Confirmation screen**: after skills + resume + self-description are submitted,
  show the founder a plain-language overview of everything the system understood —
  editable before it's saved as the real profile. Nothing from this phase is used in
  generation (Phase 7) until it's been confirmed at least once.
- [ ] Decide and implement resume file retention: keep the parsed structured data; discard
  or keep the raw uploaded file only as long as actually needed (avoid holding sensitive
  personal data — name, employer history — longer than necessary)
- [ ] **Push + tag `phase-6-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 7 — Smart, profile-aware generation

Goal: roadmap generation (built in Phase 4) gets meaningfully upgraded for real
engineering topics — grounded in real sources instead of pure model memory, reasoning
about genuine prerequisites, mixing in project-based steps, and skipping what the learner
profile (Phase 6) already shows you know. This is the highest-complexity phase so far —
review actual generated output carefully, on a real roadmap, before trusting it.

**Search grounding (new capability — see the API note below)**
- [ ] Integrate a dedicated search API call (separate from the Gemini/Groq chat models —
  see note) during generation: before finalizing a proposed roadmap, the system checks its
  own draft structure against real search results (official docs, established curricula)
  and can revise before presenting it
- [ ] Surface grounding sources alongside the generated roadmap (even briefly — "based on
  the official Rust book's structure") so the founder can sanity-check, not just trust

**Prerequisite reasoning**
- [ ] Generation explicitly reasons about *why* step B needs step A, populating real
  `depends_on` links (not just sequential order) — the model should be prompted to justify
  each dependency, not just emit a plausible-looking list

**Project-based steps**
- [ ] Generated roadmaps mix conceptual steps with applied/project steps (e.g. "build a
  small CLI tool using traits and generics"), not just a list of topics to read about —
  this also sets up Phase 8 (Verification) better, since "did you build the thing" is a
  stronger signal than "can you explain the concept"

**Difficulty/time calibration**
- [ ] Steps carry an honest relative weight/estimate (not uniform bullet points) so a
  roadmap doesn't present a huge topic and a tiny one as equally-sized steps

**Profile-aware personalization**
- [ ] Generation reads the confirmed learner profile (Phase 6) and explicitly skips or
  condenses topics the profile shows are already known — every skip is stated plainly in
  the output ("skipping basic syntax — based on your completed C++ roadmap"), never silent
- [ ] Clarifying questions (from Phase 4) become sharper using profile context — e.g.
  "you've already covered ownership — skip it here, or just a quick refresher step?"
  instead of a generic question
- [ ] **Push + tag `phase-7-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 7.5 — Resource Discovery

Goal: generated roadmaps include curated learning resources per step, so the user doesn't
have to hunt for materials. The system finds them, the user curates them. This bridges
the gap between "here's your plan" and "here's how to actually learn it." Built on the
search grounding from Phase 7, but scoped to resource finding rather than roadmap validation.

**Resource metadata on roadmap steps**
- [ ] Extend `entries.content` JSONB for `roadmap_step` to include a `resources` array:
  each resource has `id`, `title`, `url`, `format` (enum: written, video, interactive,
  repo, book_chapter), `source_type` (official_docs, community, tutorial), `estimated_time`,
  `ai_grounding_source` (which search result found it), `user_rating` (nullable)
- [ ] During roadmap generation, AI suggests 2-3 resources per step using search grounding
  results — filtered by the user's format preferences from their learner profile
- [ ] Proposed resources are shown in the roadmap confirmation screen alongside steps —
  user can remove, reorder, or add their own before accepting
- [ ] If a user's profile says they avoid videos, no video resources are suggested unless
  explicitly requested

**Step deep view (frontend)**
- [ ] Double-click or tap a roadmap step to open a "deep view" — shows: step description,
  estimated time, what this step covers (bullet list), attached resources with links,
  user's own notes field
- [ ] Deep view is optional — roadmap list view works without ever opening it (low friction
  preserved)
- [ ] "Start Session" button in deep view — begins tracking time spent on this step
  (lightweight, no complex scheduling yet)

**Backend: session tracking (lightweight)**
- [ ] Add `session_history` to `entries.content` JSONB: array of `{started_at,
  duration_minutes, resource_used, user_feedback, completed}` — minimal fields, enough
  to learn from behavior later
- [ ] `POST /entries/{id}/sessions/start` and `.../end` — simple start/stop tracking
- [ ] **Push + tag `phase-7.5-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 8 — Verification

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
- [ ] **Push + tag `phase-8-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 8.5 — In-Content AI Assistant

Goal: when the user is viewing a resource in a step's deep view, they can select any text
and get contextual help — explanation, translation, concrete examples, or reformulation.
This turns Compass from a roadmap planner into an integrated learning environment without
losing its lightweight core. The AI voice stays self-talk, never teacher-mode.

**Select-text AI help**
- [ ] `POST /ai/explain` endpoint: accepts `{selected_text, context: {step_id,
  user_skill_level, preferred_depth, action}}` where `action` is one of: `explain`,
  `explain_with_background`, `translate`, `concrete_example`, `simplify`
- [ ] Response is generated in self-talk voice, calibrated to the user's learner profile
  (e.g., if they know C++, explain Rust ownership by contrasting with C++ pointers)
- [ ] Response appears in a side panel or modal — original resource text is never replaced
- [ ] Frontend: text selection in deep view triggers a floating toolbar with action options

**Reformulate on demand**
- [ ] "This is too hard / too big / too abstract" button in deep view — user-initiated
  restructuring, reusing the Phase 4 adaptive resurfacing engine
- [ ] AI proposes: break into smaller steps, find easier resources, or skip and revisit
  prerequisite — user approves before any edit is applied
- [ ] Track reformulation requests per step — if a step gets reformulated multiple times,
  the self-talk voice should notice ("This is the third time you've broken this step down.
  Is the topic wrong, or the approach?")

**Translation support**
- [ ] `translate` action: detect source language, translate to user's preferred language
  (stored in learner profile or inferred from browser)
- [ ] Translation is shown alongside original text, not replacing it
- [ ] **Push + tag `phase-8.5-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 9 — Follow-through

Goal: standalone captured ideas can turn into real tracked action, not just sit as text.
Also: guided learning sessions, behavioral preference inference, and a focused daily view.

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

**Guided session mode (extends Phase 7.5 session tracking)**
- [ ] Full session flow: user opens deep view → hits "Start Session" → timer runs →
  user marks "done for now" or "need help" → brief feedback prompt ("helpful? too hard?")
- [ ] Session feedback feeds into behavioral preference inference (see below)
- [ ] Post-session: system suggests next action — continue to next step, review this one,
  or take a break

**Behavioral preference inference**
- [ ] Analyze session history + step completion patterns to infer preferences:
  "always skips video resources," "completes hands-on steps faster than reading steps,"
  "reformulates steps >30 min estimated time"
- [ ] Propose inferred preferences to user on a confirmation screen — editable before
  saved to `learner_profile`
- [ ] Use inferred preferences to filter resource suggestions and calibrate step estimates
  in future roadmaps
- [ ] **Push + tag `phase-9-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 10 — Depth

Goal: the system starts noticing patterns across sessions, not just within one thread.

- [ ] Idea/roadmap threading: detect and surface recurring themes or repeatedly-stalled
  steps across separate entries
- [ ] Weekly self-talk-voice review: a summary of where things stand across all active
  roadmaps and ideas, generated once there's enough history to be meaningful
- [ ] **Push + tag `phase-10-complete`.**

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

---

## API note — what's needed for Phase 6/7/7.5/8.5

The existing Gemini/Groq setup (OpenAI-compatible chat completion endpoint) is enough for:
tone generation, roadmap drafting from the model's own knowledge, the AI extraction pass
over resume text (Phase 6), and the in-content explain/reformulate features (Phase 8.5) —
all plain text-in, text-out tasks.

It is **not** enough on its own for search grounding (Phase 7 / 7.5) — a generic chat
completion call has no live web access. This needs one new integration: a dedicated search
API (e.g. Tavily, which is built specifically for LLM-grounding use cases and has a free
tier, or Serper.dev as an alternative) called from the backend, with results passed into
the generation prompt as context. Same `.env` pattern as `GEMINI_API_KEY`/`GROQ_API_KEY`
— add a `SEARCH_API_KEY` and a small `SearchGroundingService`.

Resume file parsing itself (PDF/DOCX text extraction) needs no external API — Apache PDFBox
/ Apache POI run locally.
