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

- [x] Redesign the ideas / "Everything" view: group and collapse (by AI theme, or by
  status/significance) so it stops forcing a scroll; tasks nest tidily under their parent with
  done ones collapsible. (A Status/Theme toggle; status groups collapse done/dropped/archived by
  default, each group shows a count and folds away.)
- [x] Auto-cluster captures into themes (reuse the Phase 10 threading pattern — same
  AiJsonGenerator plumbing, a sibling to `findThreads`), shown for the founder to rename/confirm;
  a confirmed group can seed a roadmap. (New `ReviewAiService.clusterIdeas` + `POST
  /entries/cluster`, proposing groups over not-yet-themed ideas only. Confirming tags each idea
  with `content.theme` via the existing generic patch endpoint — no new table. "Draft a roadmap"
  on a confirmed theme pre-fills the existing goal→outline flow with that group's ideas.)
- [x] Better idea detail: status, linked next-step tasks, resurface history, and the AI's
  interpretation — shown clearly, editable/confirmable (same propose→approve pattern).
  (`IdeaDetailModal`: editable text, status/significance as toggle chips, linked tasks with
  check-off, and the resurface log — skip count plus each past resurface response/note — which
  *is* the AI's engagement with the idea over time; there's no separate stored "interpretation"
  of an idea elsewhere in the model to surface.)
- [x] **Push + tag `phase-14-complete`. Stop. Let the founder use this for real before continuing.**

---

## Phase 15 — Profile & learning-style depth

Goal: the profile captures *how* you learn with more than free text, and that richer picture feeds
generation and sessions.

- [x] Structured learning-preference options (selectable, not just prose): pace, theory-vs-practice
  balance, preferred session length, depth, example-first vs. principle-first. (New
  `learning_preferences` jsonb column (V10) on `learner_profile`; five single-select chip groups
  on the profile screen, validated server-side against a fixed option set per key.)
- [x] Tighten the profile layout on the Phase 11 components; make the confirm/overview cleaner.
  (Every profile block now uses the shared `<Section>` instead of a hand-rolled equivalent,
  removing the duplicated CSS and matching the uppercase-label style already used elsewhere,
  e.g. the step deep view.)
- [x] Feed the richer preferences into generation (step sizing, resource choice) and the
  guided-session next-action suggestions. (`ProfileContext.forPrompt` includes a "how they like
  to learn" line read by the outline/expand-module prompts, which now explicitly instruct sizing
  steps and choosing project-vs-concept mix from pace/session-length/theory-vs-practice, and
  leading explanations with an example vs. the principle. The post-session next-action nudge in
  `StepDeepView` also reads the session-length preference to shape its wording.)
- [x] **Push + tag `phase-15-complete`. Stop. Let the founder use this for real before continuing.**

---

## Phase 16 — Fix: Focus screen crash

Goal: Focus is currently broken — visiting it renders a raw JS error to the user
(`Cannot read properties of undefined (reading 'find')`) instead of content. This is a bug, not a
design opinion, and it should be fixed before any of the polish work in Phases 20–22 below. Almost
certainly a Phase 13 regression: `FocusScreen` (or whatever it calls) still expects the pre-Phase-13
flat `roadmap.steps` array and calls `.find(...)` on it, but roadmaps are now a tree (`children`,
per `RoadmapNodeResponse`/`RoadmapResponse`).

- [x] Find the exact break.
  - Open `frontend/src/components/FocusScreen.jsx` and grep it for `.steps`, `.find(`, and any
    reference to `orderIndex`/`currentOrderIndex` — the pre-Phase-13 `RoadmapResponse` shape had a
    flat `steps: EntryResponse[]` array and a `progress.currentOrderIndex`; the current shape has
    `children: RoadmapNodeResponse[]` (a tree) and `progress.currentStepId`/`currentStepText`
    instead (see `backend/.../roadmap/dto/RoadmapResponse.java`).
  - Check whatever hook/API call `FocusScreen` uses to fetch roadmap summaries (likely
    `listRoadmaps()` in `api.js`, which already returns the new tree shape) — the crash is in how
    the component *consumes* that response, not in the API layer.
- [x] Rewrite the broken lookup(s) to work over the tree.
  - If `FocusScreen` needs "the current step" for a roadmap, use `roadmap.progress.currentStepId` /
    `currentStepText` directly (already computed server-side) instead of re-deriving it from a flat
    array.
  - If it needs to find a specific step by id anywhere in the tree, write (or reuse) a recursive
    `findNode`-style helper the same way `RoadmapDetail.jsx` already has one — do not re-flatten
    the tree with a shallow `.find()` that only checks direct children.
- [x] Verify against real data, not just a flat roadmap.
  - Test Focus against a roadmap with at least one expanded module and one step that has substeps
    (there's already a "DevOps Cloud Engineering" roadmap with modules in the dev DB to test
    against) — both "Worth revisiting" and "Where you are" sections must render without error.
  - Also test against a fully flat, non-nested roadmap (e.g. "Learn Rust properly") to make sure
    the fix doesn't regress the simple case.
- [x] Sweep for the same latent bug elsewhere.
  - `grep -rn "\.steps\b" frontend/src` and manually check every hit against whether it assumes
    the pre-Phase-13 flat shape. `ReviewService.java`'s `RoadmapWithSteps`/`listRoadmapsWithSteps()`
    (backend) has the same latent issue noted in passing during Phase 13 work — `stepsOf()` returns
    only *direct* children, so for a nested roadmap those "steps" are actually module entries with
    a `title` field, not `text` — check whether `ReviewService.formatRoadmaps`/`formatStalledSteps`
    silently produce garbled text for nested roadmaps and fix if so.
- [x] Acceptance: navigating to Focus with the current real dev data shows no error, either
  "Nothing right now. Clear." / an actual worth-revisiting item, and either "..." never gets stuck
  or a real "where you are" summary, for both a flat and a nested roadmap.
- [x] **Push + tag `phase-16-complete`.**

---

## Phase 17 — Smarter goal intake & clarifying questions

Goal: replace the fixed "at most two questions, usually time + experience" default with an
adaptive, genuinely goal-specific clarifying flow — this was the founder's direct complaint
("the question that is asked when generating a roadmap looks not enough"). Full analysis in
`ROADMAP_INTELLIGENCE_NOTES.md` Section 1.

- [x] Rewrite `PromptTemplates.CLARIFY_SYSTEM`.
  - Remove the sentence "usually how much time they have per week and what they already know /
    have done" entirely — that's the line causing every goal to converge on the same two questions.
  - Replace with instruction to identify whichever 1–4 dimensions are *most goal-specific* — give
    the model a couple of contrasting examples in the prompt itself (without hardcoding them as the
    only options) so it understands the kind of specificity wanted, e.g. "for a language-learning
    goal that might be about existing languages spoken and an upcoming trip date; for an
    infrastructure goal it might be about target scale and whether a codebase already exists" —
    framed as *illustrative*, not as the two defaults to fall back on.
  - Keep the existing hard rules (self-talk voice, no praise/emoji, strict JSON) unchanged.
- [x] Make question count adaptive.
  - Change `{"questions": [...]}` cap from a hard "at most two" to "0 to 4, as few as truly needed."
  - `RoadmapAiService.clarifyingQuestions` currently does
    `questions.subList(0, Math.min(2, questions.size()))` — remove the `Math.min(2, ...)` clamp (or
    raise it to 4) so the model's own judgment on count isn't being overridden by a hardcoded
    subList cut.
  - Handle the zero-questions case end to end: `GenerateRoadmapResponse.needsClarification` and
    `GenerateRoadmapScreen`'s `questions` phase must both handle an empty `questions` list by
    skipping straight to drafting (with the model expected to state its assumptions in the outline
    step instead) rather than rendering an empty question form.
- [x] Add a second clarification round.
  - Extend `GenerateRoadmapRequest` with a way to distinguish "first round answered, want a
    follow-up" from "ready to draft" — e.g. add a `finalRound: boolean` flag the frontend sets once
    it's done, or track a round counter. Keep the wire format additive so nothing existing breaks.
  - Add a new prompt (e.g. `PromptTemplates.FOLLOWUP_CLARIFY_SYSTEM`) that takes the goal + first
    round's Q&A and returns 0–2 follow-up questions *only if genuinely warranted* — explicitly
    instruct the model that returning an empty list is the common/expected case, so this doesn't
    become a de facto third hoop for every goal.
  - In `GenerateRoadmapScreen`, after the first `questions` phase is answered, call the new
    follow-up check; if it returns questions, show one more short round; if empty, go straight to
    drafting. Keep total added friction to at most one extra screen.
- [x] Add the "here's what I understood" paraphrase for ambiguous goals.
  - Decide the ambiguity signal: either have the clarify call itself flag `ambiguous: true` plus a
    `interpretation` string when it detects a goal with genuinely different possible meanings, or
    do this as part of the outline call (simpler — outline generation already runs once
    clarifications are answered, so it could return `{interpretation, title, modules, ...}` and the
    frontend shows the interpretation line above the outline for a quick confirm/edit before the
    founder starts editing modules).
  - Prefer folding this into the existing outline response rather than adding a whole extra network
    round-trip, to keep the flow at the same number of screens.
- [x] Broaden the dimensions the model is allowed to ask about.
  - Update `CLARIFY_SYSTEM`'s guidance/examples to explicitly include: deadline/target date, budget
    for paid resources, tools/hardware access, motivation/context (career vs. hobby vs.
    exam-driven), solo vs. must-fit-a-team's-stack — as illustrative dimensions the model can reach
    for, not a new fixed list to replace the old fixed list with.
- [x] Make profile-driven skipping a hard rule.
  - In `CLARIFY_SYSTEM`, change "use it to make the questions sharper" (soft) to an explicit
    instruction: if the profile already answers a dimension, do not ask about it — instead state
    the assumption as a statement the founder can correct (this can show up as one of the returned
    "questions" phrased as a statement + implicit yes/no, e.g. "Assuming ~5–8 hrs/week and solid
    backend experience — different for this one?").
  - This only works if `profileContext` is actually passed into the clarify call — confirm
    `RoadmapService.generate()` already does this (it does, via `ProfileContext.forPrompt`) and
    that the prompt change actually gets acted on (test manually with a founder profile that has
    strong signal, e.g. the current dev profile's self-description/skills).
- [x] Add a "skip questions, just assume" affordance.
  - In `GenerateRoadmapScreen`'s `questions` phase, add a secondary action (e.g. `Button
    variant="ghost"`) like "Skip — just use your best guess" that calls `propose()` (or the
    outline-drafting call) with empty/unanswered clarifications, relying on the model to state its
    assumptions plainly in the resulting outline (ties into the paraphrase/assumption-stating work
    above).
- [x] Acceptance: test with at least three different goal shapes (a narrow specific one with a rich
  profile, a broad vague one, and one that's genuinely ambiguous like "learn Rust") and confirm the
  clarifying questions are visibly different/sharper per goal, not the same two questions every
  time. (Live-verified for the narrow/specific case — a deadline-and-exam goal came back with four
  genuinely goal-specific questions about AWS hands-on access, weak areas, and use case, not the
  old generic "time + experience" pair. Both AI providers hit real daily quota exhaustion from the
  volume of testing across this session right after, so the broad-goal and ambiguous-goal/
  interpretation-field cases are code-reviewed but not yet live-verified — worth a quick spot-check
  once quota resets, expected in well under a day.)
- [x] **Push + tag `phase-17-complete`. Stop. Let the founder use this for real before continuing.**

---

## Phase 18 — Complexity-aware structure (assessment step, flat/nested gating, cross-module deps)

Goal: give generation one explicit, shared, structured understanding of "how big/complex is this
goal, for someone at what level" instead of every prompt (outline, expand-module) independently
re-guessing scope from raw text — the single highest-leverage structural change from
`ROADMAP_INTELLIGENCE_NOTES.md` Section 2. Everything downstream (module count, flat-vs-nested,
step sizing) should read from this one source of truth instead of guessing separately.

- [x] Add the explicit assessment pass.
  - New method `RoadmapAiService.assessGoal(String goal, String clarifications, String
    profileContext, String groundingContext)` returning a new record, e.g.
    `GoalAssessment(int complexity, Integer estimatedTotalHours, String domain, String priorLevel,
    String shape)` where `shape` is strictly `"flat"` or `"nested"`.
  - New prompt pair in `PromptTemplates` (`ASSESS_SYSTEM` / `assessUser`) — keep the self-talk-voice
    rules irrelevant here since this output is never shown to the founder directly (it's an
    internal signal), so the system prompt can be more matter-of-fact/analytical than the
    user-facing ones; still strict JSON only.
  - Call this once in `RoadmapService.generate()` right after clarifications are answered, before
    the outline call. Thread the resulting `GoalAssessment` into both `outlineUser`/`OUTLINE_SYSTEM`
    and (later, when a module is expanded) `expandModuleUser`/`EXPAND_MODULE_SYSTEM` as an extra
    plain-text block (e.g. "Assessed shape: nested, complexity 4/5, ~60 hours, prior level:
    intermediate backend, no Rust experience") so every downstream prompt reads the same numbers
    instead of re-deriving them.
  - Decide what happens if the assessment call fails (same graceful-degradation pattern as
    everywhere else in this codebase: fall back to today's un-assessed behavior — treat as
    `shape: "nested"` with mid-range complexity — rather than blocking generation entirely).
  - Done. Live-verified: "read chapter 3 of the book on my desk this week" → `complexity: 1`,
    `shape: "flat"`; "become a distributed systems engineer…" and "learn conversational Spanish
    for a trip in 3 months" → `complexity: 5`/`3`, `shape: "nested"`. Failed-assessment fallback
    (nested, complexity 3) implemented in `RoadmapService.draft()` but not separately forced/tested.
- [x] Gate outline-then-expand behind `shape`.
  - When `shape == "flat"`, skip the module-outline step and go straight to a single flat step
    list. This effectively revives something like the pre-Phase-13 `proposeRoadmap`/`PROPOSE_SYSTEM`
    path that was removed in Phase 13 (check git history — `git log -p --all -- '*/PromptTemplates.java'`
    around the Phase 13 commit for the old `PROPOSE_SYSTEM`/`proposeUser` text to reuse as a
    starting point) but now scoped by the assessment instead of being the only path.
  - `RoadmapService.generate()` needs to branch on `assessment.shape()`: nested → today's
    `moduleOutline` call; flat → a flat step-list call, returned via a variant of
    `GenerateRoadmapResponse` (could reuse the existing `proposal` status/shape for this, since it
    already matches "title + steps" exactly).
  - `GenerateRoadmapScreen` needs a corresponding branch: if the response status is `proposal`
    (flat), skip straight to the existing `StepProposalEditor` reused for module-expand (Phase 13
    already built this component to be shared — reuse it here for the top-level flat case too)
    instead of the outline-editing UI; accepting creates the roadmap with `draftSteps` directly
    (the existing `createRoadmap({title, draftSteps})` path already supports this).
  - Done. Live-verified end to end: the small-goal test above returned `status: "proposal"` with
    a real flat step list (dependsOn chained correctly), shown via `StepProposalEditor` and
    created with `draftSteps`; the nested-goal tests returned `status: "outline"` as before.
- [x] Derive module/step count bands from the assessment instead of hardcoding them.
  - Replace `OUTLINE_SYSTEM`'s fixed "Between 2 and 8 modules" and `EXPAND_MODULE_SYSTEM`'s fixed
    "Between 3 and 8 steps" with instructions that reference the passed-in complexity/hours, e.g.
    "size the number of modules to the assessed complexity and estimated hours — don't apply a
    fixed count regardless of scale."
  - Keep *some* outer safety rail (e.g. never propose more than ~10 modules or ~10 steps in one
    call) purely as a parsing/UX safety net, not as the primary sizing mechanism.
  - Done. Prompts now reference the assessed complexity/hours instead of a fixed range, with a
    10-item outer safety rail kept. Live-verified the scaling direction (a complexity-1 flat goal
    got 4 steps; a complexity-3 nested goal got 6 modules) — did not get a live sample of the
    complexity-5 case's actual module count due to repeated tertiary-provider timeouts on that
    specific heavy prompt (see `generation-max-tokens` note below).
- [x] Add cross-module `dependsOn`.
  - Current limitation: `RoadmapAiService.parseSteps` validates `dependsOn` as `dep < i` within the
    *current batch only* — a module expansion has no visibility into other modules' step ids at
    all right now.
  - Change `RoadmapService.expandModule` to pass a compact list of prior (already-expanded)
    modules' step texts + real entry ids into `expandModuleUser` (e.g. "Earlier steps you can
    depend on: [id=201] Set up a Go dev environment; [id=202] ...") and change the expand-module
    JSON contract so `dependsOn` can either be a same-batch index (as today) *or* one of these real
    prior ids — needs a small tagged-union-style field or two separate optional fields
    (`dependsOnIndex` for same-batch, `dependsOnEntryId` for cross-module) to keep parsing
    unambiguous.
  - `RoadmapService.addStepsToModule`/`createDraftSteps` needs updating to resolve a real
    cross-module id directly (no index translation needed, since it's already a real entry id)
    alongside the existing same-batch index resolution.
  - Done, code-reviewed but NOT live-verified end to end: implemented as two separate optional
    fields (`dependsOnIndex`, `dependsOnEntryId`) exactly as suggested here, with
    `parseSteps`/`RoadmapService.expandModule` gathering earlier modules' real step ids and
    `StepProposalEditor` showing a read-only "needs (earlier module): …" label. Same-batch
    `dependsOn` chaining WAS live-verified (a real 7-step module expansion resolved every
    same-batch prerequisite to its real id correctly); getting the AI to actually choose a
    cross-module id in one live call was blocked by repeated NVIDIA tertiary-provider
    timeouts/quota exhaustion on both other providers during this session — re-verify live once a
    provider has real headroom again.
- [x] Add total-estimated-time rollup.
  - Add a method (backend, since resource data + tree traversal both live there) that walks the
    whole tree's `resources[].estimatedTime` strings, parses the common formats already produced by
    generation (`~30 min`, `~1h`, `~2h`, "a few hours" — decide whether to parse the free-text ones
    or only sum the ones matching a strict pattern, dropping the rest silently), and returns a
    total. Surface it in `RoadmapResponse` (e.g. `progress.estimatedTotalMinutes`) and render it in
    `RoadmapDetail` next to the existing progress bar.
  - Done. Live-verified against a real, pre-existing roadmap's actual resource data (not just new
    test data): `progress.estimatedTotalMinutes: 870` computed correctly across its mix of
    `~30 min`/`~1h`/`~2h`/`~4h` resources, correctly and silently dropping the one free-text
    `"a few hours"` entry that doesn't match the strict pattern. Rendered in `RoadmapDetail` next
    to the progress count as `· ~Xh Ym`.
- [x] Add AI-assisted outline maintenance.
  - "Regenerate this module": new endpoint (e.g. `POST /roadmaps/{id}/modules/{moduleId}/regenerate-scope`)
    that re-runs a scoped version of the outline call for just that module's title+scope, given the
    rest of the roadmap's modules as context (so it doesn't duplicate/contradict siblings) — propose
    → approve → apply, same pattern as everything else.
  - "Insert a module here": similar shape, generates one new module (title + scope) at a chosen
    position, shown for edit/accept before it's actually inserted.
  - "Replan remaining unexpanded modules given progress so far": takes the roadmap's current state
    (what's done, what's been reformulated/skipped a lot) and offers to regenerate every module that
    hasn't been expanded yet, leaving completed/in-progress modules untouched. This is the most
    involved of the three — fine to sequence last within this phase, or explicitly punt to a later
    phase if time-boxing this one is needed, but don't drop it silently.
  - "Regenerate this module" and "Insert a module here" are done and live-verified end to end
    (both propose and apply halves): `POST .../regenerate-scope` and `POST .../insert-proposal`
    return a `{title, scope}` proposal that stays genuinely distinct from sibling modules (tested
    against a real 6-module roadmap — regenerating one module and proposing a 7th, "Cost
    Optimization", both avoided re-covering existing modules); `PUT .../modules/{moduleId}` and
    `POST .../modules` apply the accepted result. Frontend: a shared `ModuleProposalModal` (propose
    → edit → accept) wired into `RoadmapDetail` — "Regenerate scope" next to "Expand this module"
    on each unexpanded module row, "+ Insert a module" replacing "+ Add step" at the bottom of a
    module-based roadmap's list.
  - **"Replan remaining unexpanded modules" is now implemented too**, same propose→approve→apply
    shape: `POST .../modules/replan-proposal` finds every not-yet-expanded module, gives the model
    the already-expanded modules' real progress (title/scope/done-of-total) as fixed context, and
    redrafts the rest — same count, same order, only the title/scope change. Rejects a response
    that doesn't return exactly one redraft per remaining module (a restructure the model was told
    not to do by default) rather than risk misaligning ids to modules. `PUT .../modules/replan`
    applies the accepted set. Frontend: `ReplanModulesModal`, wired into `RoadmapDetail` as a
    "Replan remaining modules" button next to "+ Insert a module" (shown only when at least one
    module is still unexpanded). Code-reviewed and follows the identical, already-proven pattern as
    regenerate-scope/insert-module — not independently live-verified: two real attempts against the
    live "DevOps Cloud Engineering" roadmap (4 remaining modules) both hit the tertiary provider's
    read-timeout rather than a code error (Groq's daily quota was still ~25+ minutes from reset both
    times, per its own error message). Re-run once a fast provider has real headroom.
- [ ] Note: model routing by task complexity (cheap/fast model for a trivial goal, a stronger one
  for an ambiguous/large goal) is explicitly **not** a task here — it would mean going beyond the
  Gemini/Groq provider constraint in CLAUDE.md Section 3. Worth revisiting only if that constraint
  is ever deliberately reopened.
- [x] Acceptance: generate a roadmap for a genuinely small goal (e.g. "read one book chapter this
  week") and confirm it takes the flat path with no outline/expand round-trip; generate one for a
  large goal (e.g. "become a distributed systems engineer") and confirm module count/step sizing
  visibly scales up versus a mid-size goal: this is the direct test of "flexible based on content,
  complexity, background" from the original ask.
  - Verified: small goal → flat, 4-step proposal, no outline round-trip. Mid goal (Spanish, 3
    months) → nested, 6 modules. The large goal (distributed systems engineer) correctly assessed
    as `complexity: 5`/`nested` on every attempt, but the actual module-outline generation call for
    it repeatedly failed on the tertiary provider (NVIDIA free tier) — first via a genuine ~90s+
    read timeout, then via the response getting truncated mid-JSON at the old 1200-token budget
    (fixed by raising `compass.ai.generationMaxTokens` to 2200), then via timeout again. Groq and
    Gemini were both quota-exhausted for this entire session, so there was no working fast provider
    to fall back to for the single heaviest possible prompt. The assess→branch mechanism itself is
    confirmed correct (it correctly reached the "nested, complexity 5" verdict every time); only the
    *actual outline content* for the most extreme case wasn't captured live. Re-run this specific
    case once a fast provider has quota again.
- [x] **Push + tag `phase-18-complete`. Stop. Let the founder use this for real before continuing.**
  - Every planned task above is implemented, code-reviewed, and compiles/builds clean; most were
    also live-verified end to end this session (assessment, flat path, nested path, same-batch
    step dependencies, total-time rollup, module regenerate/insert, the generation-progress job
    pipeline). The two still-open live-verification gaps (cross-module `dependsOn`, the single
    largest-goal outline content, and now the replan-remaining-modules propose call) are all
    blocked on the same external cause — Groq's and Gemini's free-tier quotas were exhausted for
    this entire session and NVIDIA's free tier intermittently exceeds its own timeout on heavier
    prompts — not on anything left to build. Tagging now per the founder's explicit call; spot-check
    these three once a fast provider has real quota again.

**Added mid-phase, not originally scoped here:** heavy testing this session exhausted Groq's and
Gemini's free-tier daily quotas, pushing most real calls onto the NVIDIA tertiary provider — free,
but genuinely slow (30–90s per call, shared/queued GPU infra). A blocking `/roadmaps/generate`
request could silently sit for minutes with zero indication of whether it was working or stuck.
Fixed with a background job + polling pipeline: `POST /roadmaps/generate/start` returns a
`jobId` immediately; `GET /roadmaps/generate/jobs/{jobId}` reports `{status, stage, result,
error}`, where `stage` is one of `CLARIFYING`/`ASSESSING`/`DRAFTING`/`FINDING_RESOURCES` — the
same stages the existing generation flow already has, now instrumented and observable. The
frontend polls every 1.2s and shows the live stage plus a ticking elapsed-time counter instead of
a frozen button. In-memory job store (`ConcurrentHashMap`, not a DB table — single-user, no auth,
a lost job on restart just means "regenerate"), swept on a 10-minute TTL after completion. Job
survives independently of the HTTP connection that started it, so it can be started on one device
and polled from another. Live-verified end to end in the browser: watched a real generation go
CLARIFYING-skip → ASSESSING → DRAFTING → FINDING_RESOURCES → done, with the elapsed counter
ticking correctly throughout (one real run took 59s+ before resolving — exactly the case this was
built for). `expandModule`/module-maintenance calls are NOT yet wrapped in this pipeline — same
slow-call problem exists there, worth doing as a follow-up if it's still a pain point.

---

## Phase 19 — Substep richness, resource feedback loop, and generation self-checks

Goal: fix the concrete inconsistency where "break this step down" produces a visibly poorer result
than every other generation path, close the resource-usage feedback loop CLAUDE.md Section 2
already promises but doesn't yet implement, and add cheap self-checks on generated plans. Full
analysis in `ROADMAP_INTELLIGENCE_NOTES.md` Sections 5, 6, and 7.

- [ ] Fix `BREAKDOWN_SYSTEM`/`breakdownUser` to receive profile + grounding context.
  - Change `breakdownUser(String roadmapTitle, String stepText)` to
    `breakdownUser(String roadmapTitle, String stepText, String profileContext, String
    groundingContext)`, appending both the same way `outlineUser`/`expandModuleUser` already do
    (`appendProfile(sb, profileContext)` + a "Real search results..." block).
  - `RoadmapAiService.breakDownStep` needs the extra params threaded through; `ReformulateService`
    (the only caller) needs to fetch `profileContext` (same `profileService.confirmedProfile()` +
    `ProfileContext.forPrompt` pattern used in `RoadmapService`) and ground the stalled step's text
    via `SearchGroundingService.ground(stepText)` before calling it.
- [ ] Change `breakDownStep`'s return shape to match module-expansion steps.
  - Update `BREAKDOWN_SYSTEM`'s JSON contract from `{"steps": ["...", "..."]}` to the same shape
    `EXPAND_MODULE_SYSTEM` uses: `{"steps": [{"text", "kind", "weight", "dependsOn", "rationale"}]}`
    (resources can be added in the same call via the existing `suggestResources` pipeline, reusing
    the exclude-set dedup already built in Phase 13, or as a small follow-up call — prefer folding
    it into one call if the token cost is acceptable).
  - `RoadmapAiService.breakDownStep` return type changes from `List<String>` to `List<DraftStep>`
    (the existing record already used by `proposeRoadmap`/`expandModule`).
  - `RoadmapService.splitStep(Long roadmapId, Long stepId, List<String> replacements)` needs its
    signature updated to accept the richer shape (`List<DraftStepInput>` or equivalent) and persist
    kind/weight/rationale/resources on each created substep, not just `text`.
  - `ReformulateService`/`ReformulateController`/the frontend `ReformulatePanel`/`StepProposalEditor`
    usage for the break-down path all need updating to carry the richer per-step fields through the
    propose → edit → apply round trip, reusing `StepProposalEditor` (already shared/generic) rather
    than hand-rolling a second editor.
- [ ] Decide and enforce a substep nesting depth limit.
  - Pick a hard cap (2 or 3 levels total, including the top-level module) and enforce it
    server-side in `RoadmapService.splitStep` (reject/error if the target step's depth is already
    at the cap) and reflect the cap in the UI (e.g. "This is too much" / break-down action hidden
    or disabled once a step is already at max depth, with a plain-voice explanation why).
  - Manually test the Tree view's indentation and collapse/expand behavior at the chosen max depth
    with real multi-line step text, and fix any visual breakage found (this may feed into Phase 21
    if it turns out to be primarily a CSS issue rather than a logic one).
- [ ] Add a "promote back up" path.
  - Flatten: new `RoadmapService` method that takes a container step (one with substeps) and either
    (a) requires it have no meaningful substep progress and just deletes the substeps, reverting the
    parent to a plain leaf, or (b) is only offered as an option and explicitly warns/confirms since
    it's destructive to sub-progress — decide which and document the choice.
  - Graduate: new method that takes one substep and reparents it to become a sibling of its current
    parent (under the same module/root), preserving its own substeps if any, adjusting
    `order_index` on both the old and new sibling lists.
  - Wire both into a `Menu` item on the relevant step/module rows in `RoadmapDetail`.
- [ ] Wire up (or remove) `formatPreferences.prefer`.
  - Decide first: is this worth building, or worth deleting? If building: add "prefer" chips to
    `ProfileScreen` (mirroring the existing "avoid" chips, probably in the same `Learning formats`
    section) so there's actually a way to set it, then pass `formatPreferences.prefer` into
    `resourceSuggestUser` alongside the existing avoid list, with prompt wording like "prefer these
    formats when a real result fits, but never invent one that doesn't exist" (keeping the
    no-hallucination rule intact). If removing: delete the dead `prefer` handling from
    `ProfileService.sanitizeFormatPreferences`, `LearnerProfile`, `ProfileResponse`,
    `SaveProfileRequest` cleanly (don't leave a half-dead field).
- [ ] Close the resource-usage and pace-calibration feedback loop.
  - This is the biggest lift in this phase — scope it as: (a) a read path that aggregates a
    founder's history (which resource formats got used vs. skipped from `session_history` across
    all their roadmaps; average actual session duration vs. `estimatedTime`; `skip_count` and
    `reformulateCount` distribution) into a compact summary, similar in spirit to
    `ProfileContext.forPrompt` but built from behavior instead of stated profile; and (b) threading
    that summary into `resourceSuggestUser` (bias toward historically-used formats) and into the
    assessment/outline/expand prompts from Phase 18 (bias step sizing/pace).
  - This overlaps with the existing Phase 9 `InferenceService`/"Analyze my sessions" mechanism —
    check whether to extend that service to compute this summary (reusing its data-gathering) or
    keep them separate; whichever avoids duplicating the same aggregation logic twice.
  - Keep this founder-controlled, not silent: per CLAUDE.md's "AI interpretations are guesses, not
    facts" principle, feeding *behavioral* inference into generation should probably route through
    the same confirm-before-trust pattern Phase 9's inferred-preferences already uses, rather than
    silently altering generation the moment enough history exists.
- [ ] Add multi-query grounding per module.
  - In `RoadmapService.expandModule`, replace the single `searchGrounding.ground(groundingQuery)`
    call with 2–3 calls using varied query framings (e.g. `"{module} official documentation"`,
    `"{module} project ideas"`, `"{module} common mistakes beginners make"`), merge the results
    (dedup by URL), and pass the merged set into both `roadmapAi.expandModule` and
    `roadmapAi.suggestResources`. The existing `SearchGroundingService` TTL cache already makes
    repeated/similar queries cheap, so this doesn't need new caching infra — just more calls.
- [ ] Add a self-consistency/self-critique pass.
  - After a module outline or module expansion is generated, add one more cheap AI call that takes
    the draft + the original scope/goal and asks a strict "does this fully and only cover what it
    claims — list anything missing or extraneous" check, returning either "ok" or a short list of
    issues. Decide what happens on a flagged issue: auto-retry once with the critique folded back
    into the prompt, or surface the critique to the founder as an optional "the system noticed..."
    line they can ignore. Prefer the quieter auto-retry-once approach to avoid adding a new
    always-visible UI element for something that should usually be invisible.
- [ ] Add verification-triggered prerequisite suggestions.
  - When a Phase 8 verification (`VerificationService`) comes back with a named gap (`gap` field
    already exists on the verify result per the existing `verify-gap` UI class), check whether that
    gap plausibly maps to a missing prerequisite and, if so, surface a suggested `add_prerequisite`
    reformulation the founder can accept/dismiss (reusing the existing `ReformulatePanel`/propose-
    apply flow) rather than only ever being founder-triggered.
- [ ] Acceptance: break down a step and confirm the resulting substeps carry kind/weight/resources
  (not just plain text); confirm a founder profile's known skills aren't re-taught in a break-down;
  confirm resource suggestions for two different modules never repeat a URL (already true) and now
  also don't repeat across a break-down's substeps either.
- [ ] **Push + tag `phase-19-complete`. Stop. Let the founder use this for real before continuing.**

---

## Phase 20 — Design system: color roles, iconography, typography, empty states

Goal: fix the cross-cutting design-system issues that affect every screen, before touching
individual screens in Phases 21–22. Full analysis in `UI_UX_NOTES.md` Section 1.

- [ ] Add a distinct "link" color role, separate from `--brass`.
  - Add a new token to `frontend/src/index.css` (e.g. `--link: <some value distinct from brass>` —
    pick something that still reads as "on-brand" against the dark palette but is visually
    distinguishable from the brass accent at a glance; a desaturated blue or teal is the
    conventional "this is a hyperlink" signal and would contrast clearly against brass without
    clashing with the existing danger/brass hues).
  - Apply it everywhere a resource/external link currently uses brass: `StepDeepView.css`
    (`.deep-resource-title`), `LearningPathView`'s resource links, `ExpandModuleModal`/
    `StepProposalEditor`'s `.gen-resource-title`, `ReformulatePanel`'s resource link.
  - Leave brass exactly as-is for primary buttons, active nav, selected chips, progress fill — only
    the external-link usages change.
- [ ] Add an external-link affordance.
  - Pick one consistent treatment (a small ↗-style glyph after the link text, or a distinct
    underline style shown by default rather than only on hover) and apply it via a single shared
    approach (e.g. a small `ExternalLink` wrapper component in `components/ui/` that renders
    `<a target="_blank" rel="noreferrer">` plus the chosen affordance, so every resource link in
    the app goes through one place) rather than styling each usage site separately.
- [ ] Add a small icon set for recurring actions.
  - Scope deliberately small: kebab (already `⋯`, keep), delete, archive, drag-handle (already `⠿`,
    keep). Decide on an approach — inline SVG components (no external icon library dependency,
    consistent with "don't introduce new frameworks without flagging it" from CLAUDE.md Section 3)
    kept in `components/ui/icons/` or similar, sized via the existing spacing scale.
  - Apply to `Menu` items (Archive/Delete/Undo/etc. currently text-only) and anywhere a delete/
    archive action appears as a bare text button.
- [ ] Add color/weight-coding for status or type in long lists.
  - Use the Events screen's severity-left-border pattern (`AdminEventsScreen.css`) as the literal
    template — check its exact implementation and reuse the same mechanism (a colored left border
    keyed off a status/severity value) for: the Everything view's type tag (idea vs. roadmap vs.
    task) and/or status, and optionally the roadmap tree's step state marker colors (currently only
    brass-for-current, everything else faint).
- [ ] Handle long text consistently.
  - Decide on one approach (CSS line-clamp with a "show more" toggle, vs. truncate-to-one-line with
    full text always available on open) and apply it in both `Roadmap.css` (tree/Path rows) and
    anywhere else long AI-generated text renders without truncation today. Fix the existing
    mid-word ellipsis cutoff in `AllEntriesScreen`/`AllEntries.css` as part of the same pass (word-
    boundary-aware truncation, e.g. via CSS `text-overflow: ellipsis` plus a max-width that's
    already word-wrapping-safe, or truncate in JS at a word boundary).
- [ ] Revisit empty/low-content states.
  - Capture, the Draft-with-AI goal prompt, and an empty/one-item Roadmaps list all currently center
    a title + input in a mostly-black viewport. Keep Capture's essential simplicity (must stay fast,
    must stay a blank canvas per CLAUDE.md's low-friction principle) but consider: a narrower
    content column instead of full-viewport centering, a subtle background treatment (e.g. a very
    faint radial gradient or texture using existing tokens, nothing decorative/loud), or simply
    anchoring content higher on the page (e.g. starting ~120px from the top) rather than dead-center
    of the full viewport height, which is what makes it read as "vacant" at real screen sizes.
- [ ] Adopt the shared type scale everywhere.
  - Grep every component CSS file for hardcoded `rem`/`px` font-size values on headings/section
    titles and replace with the matching `--text-*` token from `index.css` where one already fits;
    only introduce a new scale step if a real gap is found (unlikely — the scale already spans
    `xs` through `2xl`).
- [ ] Lower priority: add `prefers-reduced-motion` handling.
  - Wrap existing `transition`/`animation` CSS rules (hover states, modal open/close, collapse
    toggles) in a `@media (prefers-reduced-motion: no-preference)` guard, or set durations to near-
    zero under `@media (prefers-reduced-motion: reduce)`.
- [ ] Acceptance: resource links are visibly a different color from primary buttons on at least one
  real screen (deep view or Path view); at least one recurring action (Archive or Delete) renders
  with an icon, not just text; the Everything view's ellipsis truncation no longer cuts mid-word.
- [ ] **Push + tag `phase-20-complete`. Stop. Let the founder use this for real before continuing.**

---

## Phase 21 — Screen polish: the core loop (Capture, Roadmaps, Draft-with-AI, Tree/Path/deep view)

Goal: apply the per-screen fixes from `UI_UX_NOTES.md` to the screens in the actual capture →
roadmap → learn loop, building on the Phase 20 design-system foundations.

- [ ] Capture screen (`CaptureScreen.jsx`/`.css`).
  - Give the textarea/input a subtle visible border or background tint by default (not just on
    focus), so the input area reads as a field rather than placeholder text sitting on bare
    background.
  - Increase the visual distinction between the mic button and the Capture button once there's
    text entered — e.g. the Capture button could gain the primary-brass treatment only once the
    field is non-empty (mirroring how disabled/enabled states already differ, just make the
    difference more visible, e.g. a filled background vs. outline-only).
  - Add a small first-time (or always-present but minimal) label near the big/small toggle
    explaining significance, without turning it into a permanent wall of text — e.g. a `title`
    tooltip attribute at minimum, or a one-line faint caption above the pills the first few times
    the screen is seen.
- [ ] Roadmaps list (`RoadmapsScreen.jsx`/`Roadmap.css`).
  - Adjust the empty-state message and layout so 0, 1, and many roadmaps all look intentional — e.g.
    for 0, the existing empty-state text is fine; for exactly 1, consider a bit of visual framing
    (a subtle divider or slightly narrower max-width) so a single card doesn't read as a stray leftover
    in a mostly-empty page.
  - Give the "Archived (N)" link more visual weight — currently `--faint` text with no border; bump
    it toward `--muted` or add a light border/pill treatment so it reads as a real navigation
    target, not a footnote.
- [ ] Draft-with-AI flow (`GenerateRoadmapScreen.jsx`/`.css`, `NewRoadmapScreen.css`).
  - Differentiate the goal-entry screen from Capture visually — e.g. a slightly larger heading size
    (using the type scale from Phase 20), or a bordered card around the textarea+buttons instead of
    them floating directly on the background, signaling "this is a bigger, slower action."
  - Give each module in the outline-editing screen its own bordered block/card (reusing the shared
    `Card` component from `components/ui/`) instead of plain stacked `step-input`s, especially
    valuable once there are 6+ modules to scroll through and edit.
  - Elevate "Grounded in:" sources — e.g. render as a small bordered card/callout instead of plain
    list text, so it reads as a trust signal worth noticing rather than something to skim past.
- [ ] Tree view (`RoadmapDetail.jsx`/`Roadmap.css`).
  - Give `.node-group` (module headers) a background tint (e.g. `var(--surface-2)`) or a left accent
    border distinct from a plain step row, plus indent its child steps slightly more than the
    current flush-left treatment, so the module/step hierarchy is visible without needing to read
    the text.
  - Anchor the kebab `Menu` to a fixed position (e.g. `align-self: flex-start` plus a fixed top
    offset) so it doesn't visually drift relative to wrapped multi-line step text.
  - Add a small margin between wrapped step text and its badge row (`.step-tags`) — currently badges
    sit directly under wrapped text with minimal separation.
  - Give `.step-needs` (the "needs: ..." prerequisite line) more visual weight — e.g. `var(--muted)`
    instead of `var(--faint)`, or a small left accent mark — since it represents a real dependency,
    not just an ordering hint.
- [ ] Path view (`LearningPathView.jsx`/`Roadmap.css`).
  - Add a small, faint module-name label per step (or once per contiguous run of steps from the same
    module) — the tree/leaf data already carries enough to know which module a leaf came from (walk
    up from the leaf via the roadmap's children structure, or have the backend include a
    `moduleTitle` field per leaf in `RoadmapResponse.Progress`/a new field on each Path-relevant
    node to avoid re-deriving it client-side).
- [ ] Deep view + reformulate modal (`StepDeepView.jsx`/`.css`, `ReformulatePanel.jsx`/`.css`).
  - Fix modal-on-modal stacking: either have `ReformulatePanel` replace the deep view's content in
    place (same modal, swapped body) rather than opening as a second overlay, or keep two modals but
    add a visible "back to step" affordance instead of relying on the founder guessing that × on the
    inner modal returns to the outer one.
  - Give "Select any text below for help" a proper affordance — e.g. a small icon prefix, or style
    it as a lightly bordered callout/pill rather than plain floating caption text.
  - Visually differentiate the three reformulate options — e.g. give "Break it into smaller steps"
    and "Something to learn first" (both structural/tree-changing) one visual treatment, and "Find
    gentler resources" (a content-only swap) a distinctly lighter one, so the weight of the action
    is legible before clicking.
- [ ] Resurfacing screen (`ResurfacingScreen.jsx`/`.css`).
  - Give the restructure-review sub-screen (shown after picking "change the plan") its own visual
    treatment — e.g. a bordered card around the proposed change, distinct from the plain
    `step-input` styling it currently reuses — so it reads as "review this AI proposal" rather than
    "fill out this form."
- [ ] Acceptance: screenshot-compare the Tree view before/after — modules should be identifiable at
  a glance without reading text; the reformulate modal no longer stacks two full-darkness overlays;
  Capture's input area is visibly a field, not placeholder text on black.
- [ ] **Push + tag `phase-21-complete`. Stop. Let the founder use this for real before continuing.**

---

## Phase 22 — Screen polish: organize & reflect (Everything, idea detail, Profile, Review, Events)

Goal: apply the remaining per-screen fixes from `UI_UX_NOTES.md`, focused on the screens for
organizing captures and reviewing the profile/history.

- [ ] Everything view (`AllEntriesScreen.jsx`/`AllEntries.css`).
  - Add type-coding within a group — e.g. a small colored dot or left border keyed by `idea` /
    `roadmap` / `task`, reusing the Phase 20 color-coding mechanism, so a mixed group doesn't render
    as one undifferentiated block of gray rows.
  - Add a visual container around an open group's contents — e.g. a subtle left border or slight
    background tint on the `<ul className="all-items">` block, so the boundary between one group's
    rows and the next group's caret is clear even in a long "Captured" group.
  - Give theme-cluster proposal cards (`.cluster-proposal`) a distinct visual treatment — e.g. a
    brass-tinted left border or badge marking "AI suggestion" — so a proposed cluster reads as a
    discovery to approve, not a data-entry form, consistent with the propose→approve pattern.
- [ ] Idea detail modal (`IdeaDetailModal.jsx`/`.css`).
  - Replace the modal's generic "Idea" title — either drop the title entirely (the textarea already
    shows the text) or replace it with something small and functional (e.g. a status glyph, or just
    remove the `title` prop passed to `Modal`).
  - Add a small label above the Status chip row and another above the Significance chip row (e.g.
    "Status" / "Significance", styled like `ProfileScreen`'s `.pref-group-label`) so the two chip
    rows read as independent selectors.
- [ ] Profile screen (`ProfileScreen.jsx`/`.css`).
  - Fix the skills-list density problem — concretely: render a skill without a set confidence as a
    compact `Chip` (name only, small ×), and only "promote" it to the full row-with-dropdown
    treatment once the founder actually picks a confidence for it (e.g. clicking the chip reveals
    the dropdown inline, or opens a tiny inline editor). This keeps a 39-skill resume import
    visually compact (a wrapped cloud of chips) while still allowing the detail to be added for
    skills the founder cares to annotate.
  - Alternatively/additionally: visually separate resume-derived skills from hand-added ones (e.g.
    two sub-groups under the Skills section) so the two sources aren't blended into one
    undifferentiated wall.
  - Move the "Unsaved changes" / "Saved." feedback so it's visible without scrolling to the very
    bottom — e.g. a small sticky save bar pinned to the bottom of the viewport once there are
    unsaved changes, or duplicate a compact indicator near the top of the page next to the title.
- [ ] Review screen (`PromptTemplates.REVIEW_SYSTEM`, backend only — no frontend change needed).
  - Tighten the hard rules to explicitly require multiple short sentences (e.g. "2–4 short
    sentences, not one long run-on — break at natural pauses") rather than leaving sentence count
    unconstrained, which is currently producing one long comma-spliced sentence.
- [ ] Events screen (`AdminEventsScreen.jsx`/`.css`).
  - Replace the `source`/`severity` `<select>` filters with the `Chip` toggle pattern used
    elsewhere (Status/Theme in Everything, format-avoid and learning-preferences in Profile) for
    visual consistency — likely a small multi-option toggle-chip row per filter dimension, reusing
    the existing `Chip` component from `components/ui/`.
- [ ] Acceptance: Profile's skill section for a 39-skill resume import fits in noticeably less
  vertical space than today (chip-cloud, not full rows, for anything without a set confidence); the
  idea detail modal's two chip rows are clearly labeled; Events' filters are chips, not dropdowns.
- [ ] **Push + tag `phase-22-complete`. Stop. Let the founder use this for real before continuing.**

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
