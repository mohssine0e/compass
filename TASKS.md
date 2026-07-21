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
- [x] Retention: cap how much accumulates (e.g. keep the last N events or auto-prune
  anything older than a set window) so this stays a lightweight recent-signal view, not an
  ever-growing log dump
- [x] **Push + tag `phase-5-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 6 — Learner profile

Goal: a place to tell the system what you already know, so future roadmap generation
(Phase 7) doesn't waste your time re-teaching things you've already learned. Every AI
interpretation of what you provide gets shown back to you for confirmation or correction
before it's trusted — same propose→approve→apply trust pattern as Phase 4, applied to your
own profile instead of a roadmap edit.

- [x] Profile data model: a `learner_profile` record (skills list with optional confidence
  level, free-text self-description, structured data extracted from an uploaded resume)
- [x] Manual skills entry: add/remove skill tags, each with an optional confidence level
  (e.g. "just started" / "comfortable" / "solid") — confidence matters here specifically
  because "I know Python" means very different things to different people, which is the
  exact ambiguity that caused the re-teaching problem in the first place
- [x] Resume/CV upload: accept PDF/DOCX, extract raw text locally (e.g. Apache PDFBox for
  PDF, Apache POI for DOCX — no external API needed for extraction itself)
- [x] AI extraction pass: send the extracted resume text to the existing AI provider,
  prompted to pull out structured skills/experience/education — this reuses the existing
  Gemini/Groq setup, no new provider needed for this step
- [x] Free-text self-description field ("how you like to learn/think") — AI-interpreted
  into a few structured traits (e.g. "prefers concrete examples," "learns fast, skips
  fundamentals if bored"), not stored as a raw guess
- [x] **Confirmation screen**: after skills + resume + self-description are submitted,
  show the founder a plain-language overview of everything the system understood —
  editable before it's saved as the real profile. Nothing from this phase is used in
  generation (Phase 7) until it's been confirmed at least once.
- [x] Decide and implement resume file retention: keep the parsed structured data; discard
  or keep the raw uploaded file only as long as actually needed (avoid holding sensitive
  personal data — name, employer history — longer than necessary)
- [x] **Push + tag `phase-6-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 7 — Smart, profile-aware generation

Goal: roadmap generation (built in Phase 4) gets meaningfully upgraded for real
engineering topics — grounded in real sources instead of pure model memory, reasoning
about genuine prerequisites, mixing in project-based steps, and skipping what the learner
profile (Phase 6) already shows you know. This is the highest-complexity phase so far —
review actual generated output carefully, on a real roadmap, before trusting it.

**Search grounding (new capability — see the API note below)**
- [x] Integrate a dedicated search API call (separate from the Gemini/Groq chat models —
  see note) during generation: before finalizing a proposed roadmap, the system checks its
  own draft structure against real search results (official docs, established curricula)
  and can revise before presenting it
- [x] Surface grounding sources alongside the generated roadmap (even briefly — "based on
  the official Rust book's structure") so the founder can sanity-check, not just trust

**Prerequisite reasoning**
- [x] Generation explicitly reasons about *why* step B needs step A, populating real
  `depends_on` links (not just sequential order) — the model should be prompted to justify
  each dependency, not just emit a plausible-looking list

**Project-based steps**
- [x] Generated roadmaps mix conceptual steps with applied/project steps (e.g. "build a
  small CLI tool using traits and generics"), not just a list of topics to read about —
  this also sets up Phase 8 (Verification) better, since "did you build the thing" is a
  stronger signal than "can you explain the concept"

**Difficulty/time calibration**
- [x] Steps carry an honest relative weight/estimate (not uniform bullet points) so a
  roadmap doesn't present a huge topic and a tiny one as equally-sized steps

**Profile-aware personalization**
- [x] Generation reads the confirmed learner profile (Phase 6) and explicitly skips or
  condenses topics the profile shows are already known — every skip is stated plainly in
  the output ("skipping basic syntax — based on your completed C++ roadmap"), never silent
- [x] Clarifying questions (from Phase 4) become sharper using profile context — e.g.
  "you've already covered ownership — skip it here, or just a quick refresher step?"
  instead of a generic question
- [x] **Push + tag `phase-7-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 7.5 — Resource Discovery

Goal: generated roadmaps include curated learning resources per step, so the user doesn't
have to hunt for materials. The system finds them, the user curates them. This bridges
the gap between "here's your plan" and "here's how to actually learn it." Built on the
search grounding from Phase 7, but scoped to resource finding rather than roadmap validation.

**Resource metadata on roadmap steps**
- [x] Extend `entries.content` JSONB for `roadmap_step` to include a `resources` array:
  each resource has `id`, `title`, `url`, `format` (enum: written, video, interactive,
  repo, book_chapter), `source_type` (official_docs, community, tutorial), `estimated_time`,
  `ai_grounding_source` (which search result found it), `user_rating` (nullable)
- [x] During roadmap generation, AI suggests 2-3 resources per step using search grounding
  results — filtered by the user's format preferences from their learner profile
- [x] Proposed resources are shown in the roadmap confirmation screen alongside steps —
  user can remove, reorder, or add their own before accepting
- [x] If a user's profile says they avoid videos, no video resources are suggested unless
  explicitly requested

**Step deep view (frontend)**
- [x] Double-click or tap a roadmap step to open a "deep view" — shows: step description,
  estimated time, what this step covers (bullet list), attached resources with links,
  user's own notes field
- [x] Deep view is optional — roadmap list view works without ever opening it (low friction
  preserved)
- [x] "Start Session" button in deep view — begins tracking time spent on this step
  (lightweight, no complex scheduling yet)

**Backend: session tracking (lightweight)**
- [x] Add `session_history` to `entries.content` JSONB: array of `{started_at,
  duration_minutes, resource_used, user_feedback, completed}` — minimal fields, enough
  to learn from behavior later
- [x] `POST /entries/{id}/sessions/start` and `.../end` — simple start/stop tracking
- [x] **Push + tag `phase-7.5-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 8 — Verification

Goal: roadmap steps can optionally require a real check of understanding before counting
as done, instead of pure self-reporting.

- [x] AI question-generation service: given a roadmap step's content, generate a fair,
  non-trivial check (question or short task)
- [x] AI evaluation service: given the user's answer, judge whether it demonstrates real
  understanding — return a specific gap, not just pass/fail, when it doesn't
- [x] Wire verification into "mark step done" as an optional gate (light-check vs. full
  check — not every step needs the same rigor; let this be configurable per step or per
  roadmap)
- [x] Handle wrong/shaky answers honestly in self-talk voice — point at the specific gap,
  don't just block silently
- [x] **Spaced retrieval on already-completed steps**: reuse the question-generation
  service to occasionally re-check a step that's already marked done, spaced out over
  increasing intervals (e.g. a few days, then weeks) — this is the single most
  evidence-backed learning technique (spaced repetition) and it's nearly free here since
  it's the same engine firing on a different trigger (completed + due for recheck, via
  the resurfacing service) rather than a new system
- [x] **Push + tag `phase-8-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 8.5 — In-Content AI Assistant

Goal: when the user is viewing a resource in a step's deep view, they can select any text
and get contextual help — explanation, translation, concrete examples, or reformulation.
This turns Compass from a roadmap planner into an integrated learning environment without
losing its lightweight core. The AI voice stays self-talk, never teacher-mode.

**Select-text AI help**
- [x] `POST /ai/explain` endpoint: accepts `{selected_text, context: {step_id,
  user_skill_level, preferred_depth, action}}` where `action` is one of: `explain`,
  `explain_with_background`, `translate`, `concrete_example`, `simplify`
- [x] Response is generated in self-talk voice, calibrated to the user's learner profile
  (e.g., if they know C++, explain Rust ownership by contrasting with C++ pointers)
- [x] Response appears in a side panel or modal — original resource text is never replaced
- [x] Frontend: text selection in deep view triggers a floating toolbar with action options

**Reformulate on demand**
- [x] "This is too hard / too big / too abstract" button in deep view — user-initiated
  restructuring, reusing the Phase 4 adaptive resurfacing engine
- [x] AI proposes: break into smaller steps, find easier resources, or skip and revisit
  prerequisite — user approves before any edit is applied
- [x] Track reformulation requests per step — if a step gets reformulated multiple times,
  the self-talk voice should notice ("This is the third time you've broken this step down.
  Is the topic wrong, or the approach?")

**Translation support**
- [x] `translate` action: detect source language, translate to user's preferred language
  (stored in learner profile or inferred from browser)
- [x] Translation is shown alongside original text, not replacing it
- [x] **Push + tag `phase-8.5-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 9 — Follow-through

Goal: standalone captured ideas can turn into real tracked action, not just sit as text.
Also: guided learning sessions, behavioral preference inference, and a focused daily view.

- [x] "Next step" answers from the resurfacing loop become their own trackable
  entry (linked via `parent_id`, type `task` or similar)
- [x] Progress states for standalone ideas: captured → developing → in motion →
  done/dropped, reflected in the list/roadmap views
- [x] Per-topic personalization: when generating the default-options list for the honest
  question, check for real history on that specific thread first (per CLAUDE.md — per-topic,
  not global) and use it if present; otherwise fall back to the generic default list. Be
  explicit (in the self-talk voice) about which mode it's in.
- [x] Daily/session focus view: a deliberately narrow view showing one resurfaced item
  (if any) plus the current step of one active roadmap — not a full list of everything.
  Optional alternate landing view, not a replacement for the plain list.
- [x] Staleness as a passive visual cue on the roadmap list (e.g. "6 days since touched"
  next to a step), separate from the active resurfacing prompt — lets the founder notice
  drift without waiting for the system to interrupt them

**Guided session mode (extends Phase 7.5 session tracking)**
- [x] Full session flow: user opens deep view → hits "Start Session" → timer runs →
  user marks "done for now" or "need help" → brief feedback prompt ("helpful? too hard?")
- [x] Session feedback feeds into behavioral preference inference (see below)
- [x] Post-session: system suggests next action — continue to next step, review this one,
  or take a break

**Behavioral preference inference**
- [x] Analyze session history + step completion patterns to infer preferences:
  "always skips video resources," "completes hands-on steps faster than reading steps,"
  "reformulates steps >30 min estimated time"
- [x] Propose inferred preferences to user on a confirmation screen — editable before
  saved to `learner_profile`
- [x] Use inferred preferences to filter resource suggestions and calibrate step estimates
  in future roadmaps
- [x] **Push + tag `phase-9-complete`. Stop. Let the founder use this for real before
  continuing.**

---

## Phase 10 — Depth

Goal: the system starts noticing patterns across sessions, not just within one thread.

- [x] Idea/roadmap threading: detect and surface recurring themes or repeatedly-stalled
  steps across separate entries
- [x] Weekly self-talk-voice review: a summary of where things stand across all active
  roadmaps and ideas, generated once there's enough history to be meaningful
- [x] **Push + tag `phase-10-complete`.**

---

## v2 — reshaping from real use

Phases 11–15 come from actually using the app. Guiding decisions:
- **Stay on PostgreSQL.** Nesting (roadmaps-in-roadmaps, steps-with-substeps) is a tree, and
  `entries.parent_id` is already a self-reference (CLAUDE.md §4) — it's a service/UI change, not
  a database swap. MongoDB would rewrite every entity/repository/query (resurfacing, verification,
  sessions all act on individual nodes) for a capability we already have.
- **Hierarchy is not branching.** Each level stays ordered/linear; we add depth, not parallel
  tracks (those stay out — see below).
- **Reuse, don't rebuild.** v2 features are built on a shared, documented component library
  (Phase 11) rather than re-implementing modals/chips/cards per screen.

---

## Phase 11 — Frontend foundation & design system

Goal: a small, documented, reusable component library and a coherent visual language, so every
later v2 feature is built by reuse — not rebuilt from scratch. Directly addresses maintainability
and the "make the UI/UX better" feedback. No behaviour change in this phase — it's groundwork.

- [x] Extract shared components from the patterns already scattered across screens: `Button`
  (primary/ghost/danger), `Modal`/overlay, `Card`, `Badge`/`Chip`, `EditableList`
  (add/remove/reorder), `Menu` (kebab), `Section`, `Field`/input. One place, one style.
- [x] Document every component — a concise one-line summary plus a detailed block (props, when to
  use, examples). Add a short `frontend/README` on the component conventions and how to add a screen.
- [x] Refactor existing screens onto the shared components with **no behaviour change** — the deep
  view, verify, reformulate, and restructure modals; trait/format/skill chips; kind/weight badges.
  (Also merged the parallel `.btn-primary`/`.btn-ghost` global CSS into `<Button>` across five
  screens, and extracted a shared `<Chip>` for the profile pills. The generation-preview
  `gen-badge` and All-view `all-tag` are left as-is — both surfaces are rebuilt in Phases 13/14
  and will pick up `<Badge>` then.)
- [x] Design pass: consistent spacing + typography scale + color usage, tasteful iconography where
  it earns its place, audited against the self-talk / low-friction philosophy (no clutter, no
  dashboard-ness). Keep it theme-aware.
  - Established the design system as tokens in `src/index.css`: a spacing scale (`--space-1..8`),
    a type scale (`--text-xs..2xl` + weights), and rounded out the color tokens (`--danger-dim`).
    Documented them in the kit README as the basis every new v2 screen composes from.
  - Unified color usage: replaced 15 hard-coded `#d98a7a` danger colors across the screens with
    `var(--danger)`; the kit stays token-only.
  - Kept it token-driven and dark-native (`color-scheme: dark`, brass accent) — no new colors,
    no clutter, no dashboard-ness; iconography left minimal (the mic is the only one that earns
    its place). **The subjective, layout-level visual polish is deliberately left for the founder
    to drive during the review gate below** — per the CLAUDE.md rule to stop and ask on taste/UX
    calls rather than guess.
- [ ] **Push + tag `phase-11-complete`. Stop. Let the founder use this for real before continuing.**

---

## Phase 12 — Roadmap UX

Goal: the roadmap views become clean and pleasant. Declutter, reorder by drag, and manage whole
roadmaps. Built on the Phase 11 components, still on the current flat model (hierarchy is Phase 13).

- [x] Declutter the step row: keep the primary action (mark done) inline; move Edit/Delete behind a
  hover/kebab `Menu`. Remove the per-step ↑/↓ reorder buttons entirely. (Insert-above and Undo
  moved into the same kebab; one "+ Add step" at the end.)
- [x] Reorder mode: one "Reorder" toggle at the top of a roadmap enables drag-and-drop; the new
  order is saved explicitly (reuses the existing reorder endpoint), not on every nudge.
- [x] Delete a whole roadmap: endpoint + UI with a clear confirm (cascades to its steps).
- [x] Archive a roadmap: an `archived` state that drops it out of the main list into an Archive
  view; unarchive to bring it back. Keeps the list focused without losing history.
- [x] Handle arbitrarily long step lists gracefully (anchor on the current step; the list stays
  usable at 5 steps or 50). (Completed steps above the current one collapse behind a
  "Show N completed steps" toggle so the roadmap opens on where you are.)
- [x] **Push + tag `phase-12-complete`. Stop. Let the founder use this for real before continuing.**

---

## Phase 13 — Hierarchical roadmaps & smarter generation

Goal: roadmaps can nest for genuinely big goals ("become an engineer" → modules → steps →
substeps), generation stops dumping one flat list, and resources stop duplicating. This is the
core product gap from real use — highest value, highest risk. Design and get sign-off before coding.

**Search grounding infrastructure (independent of the hierarchy design — safe to do first)**
- [x] Add **Exa** as the **primary** grounding provider (`EXA_API_KEY`, `/search` with
  `type: auto` + `contents.highlights`) using the same primary/fallback pattern as Gemini/Groq;
  keep **Tavily** as the automatic fallback. Normalize both providers to one result shape; fail
  over on error/empty.
- [x] Audit search calls per roadmap generation — confirm it's a small fixed number (one call for
  the goal), not one-per-step. (Result: it's 1 call per proposal; resource discovery reuses that
  single result set — no per-step fan-out.)
- [x] Basic in-memory cache for repeated/identical search queries (a dev quota saver), TTL-bounded,
  so re-running generation on the same goal doesn't burn fresh queries.

**Nesting (via the existing `parent_id` tree — no engine change)**
- [x] Generalize roadmaps from flat to tree: a roadmap can contain child roadmaps (modules) and a
  step can contain substeps. Ordering is per-parent (`order_index` already handles siblings).
  (`RoadmapNodeResponse` recursively builds the tree from `childrenOf`; a leaf is a
  `roadmap_step` with no children.)
- [x] Progress rolls **up**: a parent's progress aggregates over its leaf descendants; "current
  step" is a leaf-first traversal. Resurfacing, verification, and sessions keep operating on
  leaves. (`RoadmapResponse.of` collects leaves in pre-order and computes total/done/current from
  them; the roadmap list and resurfacing query only ever surface top-level roadmaps.)
- [x] Roadmap tree view: render nesting with collapsible modules/substeps; a simple roadmap still
  reads as a plain linear list. (Fully-done groups collapse by default; an unexpanded module shows
  its scope + an "Expand this module" action instead of being treated as a leaf.)

**Outline-then-expand generation (replaces one-shot flat generation)**
- [x] Goal → clarifying questions → a top-level **module outline** (2–8 modules, each a one-line
  scope — relaxed from a 4–8 floor so a genuinely small goal isn't padded), editable, then
  accepted — instead of one giant step list up front.
- [x] Expand on demand: "expand this module" generates its steps (grounded for that module's
  topic); "break this step down" generates substeps (reuse the Phase 4 break-down — `splitStep`
  now nests the replacements under the original step instead of deleting it, so the step becomes
  a container like a module). Depth grows only where the founder wants it.

**Resource discovery, de-duplicated**
- [x] Search per module (scoped, more relevant) instead of one goal-wide pool; keep a roadmap-wide
  set of used URLs/domains and cap one resource per topic+format, with a dedup pass before showing
  — so the same video never appears on two steps. (`suggestResources` now threads an exclude set
  through the per-step loop — fixing duplication *within* one generation call, which was the
  original reported bug — and `usedResourceUrls` walks the whole tree so a module expansion also
  avoids everything already attached elsewhere in the roadmap. The model is told the exclude list
  too, but the Java-side filter is the real guarantee.)
- [x] A "learning path" view: an explicit ordered traversal (current leaf + the next few + their
  resources + estimated time), distinct from the structural tree — ties into Focus / guided
  sessions. (`LearningPathView`, a Tree/Path toggle on the roadmap detail.)
- [x] **Push + tag `phase-13-complete`. Stop. Let the founder use this for real before continuing.**

---

## Phase 14 — Captures & organization

Goal: captured ideas and things become pleasant to view and actually get organized — not one long
scroll. Reuses the Phase 10 cross-thread engine to group, not just read.

- [ ] Redesign the ideas / "Everything" view: group and collapse (by AI theme, or by
  status/significance) so it stops forcing a scroll; tasks nest tidily under their parent with
  done ones collapsible.
- [ ] Auto-cluster captures into themes (reuse the Phase 10 threading), shown for the founder to
  rename/confirm; a confirmed group can seed a roadmap.
- [ ] Better idea detail: status, linked next-step tasks, resurface history, and the AI's
  interpretation — shown clearly, editable/confirmable (same propose→approve pattern).
- [ ] **Push + tag `phase-14-complete`. Stop. Let the founder use this for real before continuing.**

---

## Phase 15 — Profile & learning-style depth

Goal: the profile captures *how* you learn with more than free text, and that richer picture feeds
generation and sessions.

- [ ] Structured learning-preference options (selectable, not just prose): pace, theory-vs-practice
  balance, preferred session length, depth, example-first vs. principle-first.
- [ ] Tighten the profile layout on the Phase 11 components; make the confirm/overview cleaner.
- [ ] Feed the richer preferences into generation (step sizing, resource choice) and the
  guided-session next-action suggestions.
- [ ] **Push + tag `phase-15-complete`. Stop. Let the founder use this for real before continuing.**

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
- Branching / parallel tracks — non-linear roadmaps where steps fork into concurrent paths.
  Nesting/hierarchy (modules, substeps) is now planned (Phase 13) and is different: each level
  stays ordered/linear, we just add depth. True branching or parallel tracks stay out unless a
  real roadmap needs them, not just for visual polish.

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
