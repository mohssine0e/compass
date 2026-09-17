# Compass

A personal system for two things: capturing ideas before they're lost, and knowing exactly where you stand on the things you're working through (a skill roadmap, a study plan, a project) — with a "voice" that's meant to feel like your own clear-headed self-talk.

## Architecture

- **Backend**: Spring Boot (Java), handling AI tier classification, external provider orchestration (Groq, Gemini, NVIDIA NIM), embedding-based search, and PostgreSQL persistence.
- **Frontend**: React + Vite, focused on rendering interactive task cards, roadmaps, and chat modules.

## Current Project Status & Audit

*Implementation is actively underway, but several areas need refinement to scale properly.*

### What is Implemented
- **Frontend Foundation**: React + Vite configuration with standard modern tooling (Vitest, Oxlint). Basic layout, routing, and UI components (`App.jsx`, `roadmapTree.js`, `api.js`) are established.
- **Backend Architecture**: Comprehensive AI provider integration (`OpenAiCompatibleChatClient`), multi-tier fallbacks, intent classification (`IntentAiService`), and a fully featured notification system.
- **Database / Schema**: Foundational entities and the DB seeding process are implemented.

### What is Missing / Needs Improvement
- **Scaling / Performance**: 
  - There are lingering misuse of `@Transactional(readOnly = true)` leading to hidden performance costs during batch AI generation (as documented in the TASKS log). 
  - Synchronous caching of AI generation results blocks the main execution threads; this needs to be decoupled into background jobs.
- **Design / UI Resilience**:
  - The frontend currently has hardcoded breakpoints and uses brittle flexbox alignments that break under narrow mobile views (e.g., `< 390px`). 
  - Missing proper state management for complex UI trees (currently relying on prop drilling or heavy localized states in `api.js` and `roadmapTree.js`).
- **Code Organization**:
  - Some logic is overly coupled in the frontend's API and state layers. 
  - The project previously suffered from documentation bloat (too many `.md` files) which has been cleaned up to maintain focus.

## Documentation Navigation

- [`CLAUDE.md`](./CLAUDE.md) — Product philosophy, architecture decisions, data model, and conventions. Read this first for the *why*.
- [`TASKS.md`](./TASKS.md) — The prioritized, phase-by-phase build plan and issue tracker.
- [`HOW_TO_RUN.md`](./HOW_TO_RUN.md) — Instructions for setting up and running the project locally.
