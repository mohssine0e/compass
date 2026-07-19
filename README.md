# Compass

A personal system for two things: capturing ideas before they're lost, and knowing
exactly where you stand on the things you're working through (a skill roadmap, a study
plan, a project) — with a "voice" that's meant to feel like your own clear-headed
self-talk, not an AI assistant.

## Status

🚧 Pre-build. The design and build plan are finished; implementation hasn't started yet.

- [`CLAUDE.md`](./CLAUDE.md) — product philosophy, architecture decisions, data model,
  and conventions. Read this first — it's the source of truth for *why* things are built
  the way they are.
- [`TASKS.md`](./TASKS.md) — the phase-by-phase build plan, in priority order.
- [`SETUP.md`](./SETUP.md) — how to get Claude Code running against this repo.

## The short version

- **Phase 1**: capture (text/voice) + roadmap structure with self-marked progress
- **Phase 2**: the system resurfaces stalled ideas/steps and asks about them
- **Phase 3**: fixing/editing roadmaps directly — undo, edit, reorder, insert, delete steps
- **Phase 4**: AI-generated roadmaps from a goal, and adaptive resurfacing that can
  propose restructuring a stalled step, not just ask about it
- **Phase 5**: roadmap steps can require real AI-verified understanding, not just
  self-reporting, plus spaced re-checks on things already marked done
- **Phase 6**: captured ideas turn into trackable next steps
- **Phase 7**: pattern-awareness across sessions, weekly reviews

Each phase is meant to be actually used for a while before the next one gets built —
see `TASKS.md` for the reasoning.