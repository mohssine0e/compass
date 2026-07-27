// Thin API client. Backend lives under /api (proxied to Spring in dev).

const BASE = '/api'

// Every request gets a ceiling (V3-0.5). Without one, a hung connection — a provider stall
// behind the backend, a dropped wifi association that never RSTs — left a spinner turning
// forever with no error and no way back.
//
// Deliberately generous rather than tight, and one number for every path. Most endpoints here
// make a synchronous AI call, and the slow ones are genuinely slow: an expand-batch runs four
// modules against the heavy chain, where a single module can spend 40s on Gemini Pro before
// falling through to NIM's 75s override. A tighter default with a per-endpoint allowlist would
// silently break whichever AI endpoint the list forgot, which is worse than a rare CRUD call
// taking two minutes to give up. The point is to bound the hang, not to be precise about it.
const DEFAULT_TIMEOUT_MS = 120_000

// The generation job's status poll is the exception: it returns immediately by design, is called
// every 1.2s for the life of a draft, and a stuck one should be retried, not waited on.
const POLL_TIMEOUT_MS = 10_000

/**
 * Thrown when a request hit its own ceiling rather than being refused. Callers can tell the
 * difference and say "that took too long" instead of the generic failure message.
 */
export class TimeoutError extends Error {
  constructor(path) {
    super('That took too long — nothing was saved.')
    this.name = 'TimeoutError'
    this.path = path
  }
}

// Exported so tests can exercise the timeout/abort contract directly — none of the domain
// functions below forward an options object (no caller passes a signal yet; see V3-5.4), so
// there's no other way to reach this from outside the module.
export async function request(path, options = {}) {
  const { timeoutMs = DEFAULT_TIMEOUT_MS, signal: callerSignal, ...init } = options

  // Our own timeout, plus the caller's cancellation (unmount, superseded request) if given.
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  const onCallerAbort = () => controller.abort()
  callerSignal?.addEventListener('abort', onCallerAbort)

  let res
  try {
    res = await fetch(BASE + path, {
      headers: { 'Content-Type': 'application/json' },
      ...init,
      signal: controller.signal,
    })
  } catch (err) {
    // An abort we caused on a timeout reads differently from one the caller asked for — only
    // the former is a failure worth showing.
    if (err.name === 'AbortError' && !callerSignal?.aborted) throw new TimeoutError(path)
    throw err
  } finally {
    clearTimeout(timer)
    callerSignal?.removeEventListener('abort', onCallerAbort)
  }

  if (!res.ok) {
    let detail = `Request failed (${res.status})`
    try {
      const body = await res.json()
      if (body && body.detail) detail = body.detail
    } catch {
      // non-JSON error body; keep the default message
    }
    throw new Error(detail)
  }
  if (res.status === 204) return null
  return res.json()
}

/** Capture an entry. `payload` is { text, type?, significance?, parentId?, orderIndex? }. */
export function createEntry(payload) {
  return request('/entries', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/** All entries, newest first. */
export function listEntries() {
  return request('/entries')
}

/** Every completed roadmap step, most recently updated first (RB-5.3) — the Practice/Review picker. */
export function listCompletedSteps() {
  return request('/entries/completed-steps')
}

/** Partial update of an entry. `patch` is e.g. { status: 'done' }. */
export function patchEntry(id, patch) {
  return request(`/entries/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(patch),
  })
}

/** Propose theme clusters over not-yet-themed ideas. Returns [{ label, ideaIds }]. */
export function clusterIdeas() {
  return request('/entries/cluster', { method: 'POST' })
}

/** Create a roadmap. `payload` is { title, notes?, steps: string[] }. */
export function createRoadmap(payload) {
  return request('/roadmaps', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/**
 * One turn of AI roadmap drafting (Phase 18: runs as a background job, polled for progress —
 * the free-tier tertiary AI provider can take up to a minute, so a plain blocking call left the
 * user staring at a frozen button). Call with `{ goal }` to get clarifying questions back, then
 * with `{ goal, clarifications: [{ question, answer }] }` for the eventual outline/proposal.
 *
 * `onStage(stage)`, if given, is called each time the server reports a new stage
 * (CLARIFYING/ASSESSING/DRAFTING/FINDING_RESOURCES) — display-only, a fixed set of values the
 * backend defines. Throws when drafting is unavailable (mirrors the old 503 message) — fall back
 * to the manual form.
 */
export async function generateRoadmap(payload, onStage) {
  const { jobId } = await request('/roadmaps/generate/start', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
  let lastStage = null
  for (;;) {
    await sleep(1200)
    let job
    try {
      job = await request(`/roadmaps/generate/jobs/${jobId}`, { timeoutMs: POLL_TIMEOUT_MS })
    } catch (err) {
      // A dropped poll isn't a failed generation — the job runs on the server regardless. Keep
      // polling; a genuinely dead backend surfaces as FAILED or as the caller navigating away.
      if (err instanceof TimeoutError) continue
      throw err
    }
    if (job.stage && job.stage !== lastStage) {
      lastStage = job.stage
      onStage?.(job.stage)
    }
    if (job.status === 'DONE') return job.result
    if (job.status === 'FAILED') {
      throw new Error(job.error || 'Drafting is unavailable right now — write the steps yourself.')
    }
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** Draft steps for one module, grounded on its own scope. Nothing persisted. */
export function expandModule(roadmapId, moduleId) {
  return request(`/roadmaps/${roadmapId}/modules/${moduleId}/expand`, { method: 'POST' })
}

/**
 * Expand more than one module at once (Phase 19), only on explicit request — runs concurrently
 * on the backend. Each entry in the returned array is `{moduleId, result, error}`; accept each
 * one independently via `addModuleSteps`.
 */
export function expandModulesBatch(roadmapId, moduleIds) {
  return request(`/roadmaps/${roadmapId}/modules/expand-batch`, {
    method: 'POST',
    body: JSON.stringify({ moduleIds }),
  })
}

/** Accept a module's expanded steps. `draftSteps` is the same shape as roadmap creation. */
export function addModuleSteps(roadmapId, moduleId, draftSteps) {
  return request(`/roadmaps/${roadmapId}/modules/${moduleId}/steps`, {
    method: 'POST',
    body: JSON.stringify({ draftSteps }),
  })
}

/**
 * Background-draft status for this roadmap's not-yet-expanded modules — each unexpanded module
 * starts drafting automatically server-side (roadmap created, module inserted/regenerated/
 * replanned), so this is worth polling instead of always blocking on `expandModule`. Each item is
 * `{moduleId, status: 'PENDING'|'DONE'|'FAILED', result, error}`.
 */
export function getModulePrefetchStatus(roadmapId) {
  return request(`/roadmaps/${roadmapId}/modules/prefetch-status`)
}

/**
 * Resources for an already-drafted, not-yet-persisted batch of steps — the follow-up call for a
 * proposal shown before resources were ready (flat-goal proposals, reformulate/resurfacing
 * break-downs). `roadmapId` is null for a brand-new flat-goal proposal. Returns a list aligned by
 * index to `stepTexts`, each entry a (possibly empty) list of resources for that step.
 */
export function suggestResources({ scope, stepTexts, roadmapId }) {
  return request('/resources/suggest', {
    method: 'POST',
    body: JSON.stringify({ scope, stepTexts, roadmapId: roadmapId ?? null }),
  })
}

/**
 * Find resources for the already-saved steps under `parentId` (a module, or the roadmap itself
 * for a flat one) that don't have any yet. Steps that already have resources are never touched —
 * those are curated. Returns `{filled}`: how many steps gained resources.
 *
 * Unlike `suggestResources`, this writes directly rather than proposing: it only ever adds to
 * steps that were empty, so there's no existing choice of yours to review against.
 */
export function backfillResources(roadmapId, parentId) {
  return request(`/roadmaps/${roadmapId}/nodes/${parentId}/resources`, { method: 'POST' })
}

/** Redraft one module's title/scope (Phase 18). Nothing persisted; accept via `updateModule`. */
export function regenerateModuleScope(roadmapId, moduleId) {
  return request(`/roadmaps/${roadmapId}/modules/${moduleId}/regenerate-scope`, { method: 'POST' })
}

/** Apply an edited module title/scope. */
export function updateModule(roadmapId, moduleId, title, scope) {
  return request(`/roadmaps/${roadmapId}/modules/${moduleId}`, {
    method: 'PUT',
    body: JSON.stringify({ title, scope }),
  })
}

/** Draft one new module to insert (Phase 18). Nothing persisted; accept via `insertModule`. */
export function proposeNewModule(roadmapId) {
  return request(`/roadmaps/${roadmapId}/modules/insert-proposal`, { method: 'POST' })
}

/**
 * Draft one new module scoped to a specific subtopic (RB-3.8), reached from a confirmed
 * Canonical Topic Match. Returns { title, scope, possibleDuplicate }. Accept via `insertModule`.
 */
export function proposeSubtopicModule(roadmapId, focusGoal) {
  return request(`/roadmaps/${roadmapId}/modules/subtopic-proposal`, {
    method: 'POST',
    body: JSON.stringify({ focusGoal }),
  })
}

/** Insert an accepted new module. `position` is 0-based; omit to append. */
export function insertModule(roadmapId, title, scope, position) {
  return request(`/roadmaps/${roadmapId}/modules`, {
    method: 'POST',
    body: JSON.stringify({ title, scope, position }),
  })
}

/**
 * Redraft every not-yet-expanded module given real progress so far (Phase 18). Returns
 * `[{moduleId, title, scope}]`, one per remaining module. Nothing persisted; accept via
 * `applyReplan`.
 */
export function replanModules(roadmapId) {
  return request(`/roadmaps/${roadmapId}/modules/replan-proposal`, { method: 'POST' })
}

/** Apply an accepted replan. `modules` is `[{moduleId, title, scope}]`. */
export function applyReplan(roadmapId, modules) {
  return request(`/roadmaps/${roadmapId}/modules/replan`, {
    method: 'PUT',
    body: JSON.stringify({ modules }),
  })
}

/** Active roadmaps with their steps and progress, newest first (archived excluded). */
export function listRoadmaps() {
  return request('/roadmaps')
}

/** Archived roadmaps only — the Archive view. */
export function listArchivedRoadmaps() {
  return request('/roadmaps/archived')
}

/** One roadmap with its ordered steps and progress. */
export function getRoadmap(id) {
  return request(`/roadmaps/${id}`)
}

/** Archive or unarchive a whole roadmap. */
export function setRoadmapArchived(roadmapId, archived) {
  return request(`/roadmaps/${roadmapId}/archive`, {
    method: 'PUT',
    body: JSON.stringify({ archived }),
  })
}

/** Delete a whole roadmap and its steps. Not reversible. */
export function deleteRoadmap(roadmapId) {
  return request(`/roadmaps/${roadmapId}`, { method: 'DELETE' })
}

/**
 * The re-tier escape hatch (RB-2.5) — founder-triggered correction when the classifier got a
 * goal's scale wrong. Returns { status: 'applied', ... } or { status: 'proposal', proposal }.
 */
export function reTierRoadmap(roadmapId, tier) {
  return request(`/roadmaps/${roadmapId}/re-tier`, {
    method: 'POST',
    body: JSON.stringify({ tier }),
  })
}

/** Confirm a re-tier proposal from `reTierRoadmap` — applies the (possibly edited) groups/order. */
export function applyReTierProposal(roadmapId, kind, groups) {
  return request(`/roadmaps/${roadmapId}/re-tier/apply`, {
    method: 'POST',
    body: JSON.stringify({ kind, groups }),
  })
}

/**
 * The one-time CAREER completion reflection (RB-4.7) — cheap to call after any step is marked
 * done; a no-op unless this roadmap just became 100% complete. Returns { reflection } (null when
 * there's nothing new).
 */
export function checkCareerCompletion(roadmapId) {
  return request(`/roadmaps/${roadmapId}/check-completion`, { method: 'POST' })
}

/**
 * Two-way completion sync + module rollup (RB-4.8/4.12) — call right after marking a step done.
 * A no-op server-side if there's nothing to roll up either direction.
 */
export function syncStepCompletion(stepId) {
  return request(`/roadmaps/steps/${stepId}/sync-completion`, { method: 'POST' })
}

/** Reorder a roadmap's steps. `stepIds` is the full step id list in the new order. */
export function reorderRoadmapSteps(roadmapId, stepIds) {
  return request(`/roadmaps/${roadmapId}/steps/order`, {
    method: 'PUT',
    body: JSON.stringify({ stepIds }),
  })
}

/** Insert a step into a roadmap. `position` is 0-based; omit to append. */
export function insertRoadmapStep(roadmapId, text, position) {
  return request(`/roadmaps/${roadmapId}/steps`, {
    method: 'POST',
    body: JSON.stringify({ text, position }),
  })
}

/** Delete a step from a roadmap. */
export function deleteRoadmapStep(roadmapId, stepId) {
  return request(`/roadmaps/${roadmapId}/steps/${stepId}`, {
    method: 'DELETE',
  })
}

/** "Promote back up" — flatten (Phase 20): delete a container step's substeps. */
export function flattenStep(roadmapId, stepId) {
  return request(`/roadmaps/${roadmapId}/steps/${stepId}/substeps`, {
    method: 'DELETE',
  })
}

/** "Promote back up" — graduate (Phase 20): reparent a substep to be its parent's sibling. */
export function graduateStep(roadmapId, stepId) {
  return request(`/roadmaps/${roadmapId}/steps/${stepId}/graduate`, {
    method: 'PUT',
  })
}

/** "What this step covers" bullets for the deep view — generated once, then cached (Phase 7.5). */
export function getStepCovers(stepId) {
  return request(`/roadmaps/steps/${stepId}/covers`, { method: 'POST' })
}

/** Start a work session on a step. */
export function startSession(stepId) {
  return request(`/entries/${stepId}/sessions/start`, { method: 'POST' })
}

/** End the open work session on a step. `body` is { resourceUsed?, userFeedback?, completed? }. */
export function endSession(stepId, body = {}) {
  return request(`/entries/${stepId}/sessions/end`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/**
 * In-content help for selected text (Phase 8.5). `context` is
 * { stepId?, action, preferredDepth?, preferredLanguage? }. Returns { response }.
 */
export function explainText(selectedText, context) {
  return request('/ai/explain', {
    method: 'POST',
    body: JSON.stringify({ selectedText, context }),
  })
}

/**
 * A step's auto-detected default check format (Phase 26: multiple_choice/code_challenge/
 * scenario/free_response), so the format picker can preselect it. Returns { format }.
 */
export function getStepDefaultFormat(stepId) {
  return request(`/verification/steps/${stepId}/default-format`)
}

/**
 * Generate a verification check for a step (Phase 8), optionally in a specific format (Phase 26)
 * — omit to use the step's auto-detected default. Returns { format, question, options }; options
 * is only set for format "multiple_choice".
 */
export function getStepCheck(stepId, format) {
  const query = format ? `?format=${encodeURIComponent(format)}` : ''
  return request(`/verification/steps/${stepId}/check${query}`, { method: 'POST' })
}

/**
 * Answer a step's check. Returns { passed, gap }; on pass the step is marked done. `answer` is
 * the free-text answer; `selectedIndex` (Phase 26) is the chosen option's index for a
 * multiple_choice check instead — pass whichever the pending check's format calls for.
 */
export function verifyStep(stepId, answer, selectedIndex) {
  return request(`/verification/steps/${stepId}/verify`, {
    method: 'POST',
    body: JSON.stringify({ answer, selectedIndex }),
  })
}

/**
 * Draft a reformulation of a step (Phase 8.5). `kind` is 'break_down', 'add_prerequisite', or
 * 'easier_resources'. Returns a proposal to review — nothing changes yet.
 */
export function proposeReformulate(stepId, kind) {
  return request(`/reformulate/steps/${stepId}?kind=${encodeURIComponent(kind)}`, { method: 'POST' })
}

/** Apply an approved reformulation. */
export function applyReformulate(stepId, body) {
  return request(`/reformulate/steps/${stepId}/apply`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/** The one thing to resurface before capture, or null when nothing qualifies. */
export function getNextResurfacing() {
  return request('/resurfacing/next')
}

/** Cross-thread depth (Phase 10): { threads, summary, enough }. */
export function getReview() {
  return request('/review')
}

/** Record a response to a resurfacing prompt. `body` is { option, text? }. */
export function respondResurfacing(id, body) {
  return request(`/resurfacing/${id}/respond`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/** Answer a spaced recheck of a done step (Phase 8). Returns { passed, gap }. */
export function recheckResurfacing(id, answer) {
  return request(`/resurfacing/${id}/recheck`, {
    method: 'POST',
    body: JSON.stringify({ answer }),
  })
}

/**
 * Draft a restructuring of a stalled roadmap's current step. `kind` is 'break_down' or
 * 'add_prerequisite'. Returns a proposal to edit and approve — nothing changes yet.
 * Throws (503) when the AI can't help right now.
 */
export function proposeRestructure(id, kind) {
  return request(`/resurfacing/${id}/restructure`, {
    method: 'POST',
    body: JSON.stringify({ kind }),
  })
}

/** Apply an approved restructuring; returns the updated roadmap. */
export function applyRestructure(id, body) {
  return request(`/resurfacing/${id}/restructure/apply`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/** The current learner profile (Phase 6). */
export function getProfile() {
  return request('/profile')
}

/** Behaviour-inferred preferences to review (Phase 9). Returns { preferences, basis }. */
export function getInference() {
  return request('/profile/inference')
}

/**
 * Save the reviewed learner profile — this also marks it confirmed. `payload` is
 * { skills, resumeExtracted?, selfDescription? }.
 */
export function saveProfile(payload) {
  return request('/profile', {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

/**
 * Upload a PDF/DOCX resume; returns a proposed { skills, experience, education } to review.
 * Nothing is saved and the raw file isn't stored. Throws (503) when AI reading is unavailable.
 */
export function extractResume(file) {
  const form = new FormData()
  form.append('file', file)
  // No Content-Type header: the browser sets the multipart boundary itself.
  return fetch('/api/profile/resume/extract', { method: 'POST', body: form }).then(async (res) => {
    if (!res.ok) {
      let detail = `Request failed (${res.status})`
      try {
        const body = await res.json()
        if (body && body.detail) detail = body.detail
      } catch {
        // keep default
      }
      throw new Error(detail)
    }
    return res.json()
  })
}

/** Interpret a free-text self-description into proposed traits (to review). `text` is a string. */
export function interpretSelfDescription(text) {
  return request('/profile/self-description/interpret', {
    method: 'POST',
    body: JSON.stringify({ text }),
  })
}

/**
 * Recent system events, newest first. `filters` is { source?, severity?, limit? } — omit a
 * filter to include every value. Operational/admin view (Phase 5).
 */
export function getAdminEvents(filters = {}) {
  const params = new URLSearchParams()
  if (filters.source) params.set('source', filters.source)
  if (filters.severity) params.set('severity', filters.severity)
  if (filters.limit) params.set('limit', filters.limit)
  const query = params.toString()
  return request('/admin/events' + (query ? `?${query}` : ''))
}

/**
 * RB-1 tier-classifier debug screen (throwaway, not part of the real app flow): classify one
 * goal. Returns { goal, tier, confidence, reasoning }.
 */
export function classifyGoal(goal) {
  return request('/admin/classify-test/classify', {
    method: 'POST',
    body: JSON.stringify({ goal }),
  })
}

/**
 * RB-1 tier-classifier debug screen: run the exact 26-goal validation set from TASKS_v2.md in
 * one call. Returns a list of { goal, expectedTier, actualTier, confidence, reasoning, pass }.
 */
export function runClassifyTestSet() {
  return request('/admin/classify-test/run-all', { method: 'POST' })
}

/**
 * The canonical topic a roadmap was created as/from (RB-3.10), or null if it doesn't have one
 * (e.g. embeddings weren't configured when it was created).
 */
export function getCanonicalTopicForRoadmap(roadmapId) {
  return request(`/topics?roadmapId=${roadmapId}`)
}

/** Draft the specific edit a founder's addition suggestion implies — nothing applied yet. */
export function suggestTopicAddition(topicId, suggestion) {
  return request(`/topics/${topicId}/suggest-addition`, {
    method: 'POST',
    body: JSON.stringify({ suggestion }),
  })
}

/** Apply a confirmed topic addition. `field` is subtopics|prerequisites|aliases. */
export function applyTopicAddition(topicId, field, value) {
  return request(`/topics/${topicId}/apply-addition`, {
    method: 'POST',
    body: JSON.stringify({ field, value }),
  })
}

/**
 * The unified intake's first step (RB-5.1/5.2) — classify what one piece of input actually
 * wants before anything else runs. Returns { intent, confidence, reasoning }.
 */
export function classifyIntent(input) {
  return request('/intake/classify', {
    method: 'POST',
    body: JSON.stringify({ input }),
  })
}

/**
 * Everything in Compass as one JSON document (V3-0.2) — triggers a browser download rather than
 * returning data, since the point is getting a file onto disk, not rendering it. Uses a plain
 * anchor to the endpoint so the browser handles the save with the server's own filename, instead
 * of buffering the whole export through JS.
 */
export function downloadExport() {
  const a = document.createElement('a')
  a.href = BASE + '/export'
  a.download = ''
  document.body.appendChild(a)
  a.click()
  a.remove()
}

/** Per-provider AI health: what's working, what's benched, and how fast (V3-2.3). */
export function getProviderHealth() {
  return request('/admin/providers')
}

/** This one roadmap as a downloadable JSON file — the tree exactly as the app renders it. */
export function downloadRoadmapExport(roadmapId) {
  const a = document.createElement('a')
  a.href = `${BASE}/roadmaps/${roadmapId}/export`
  a.download = ''
  document.body.appendChild(a)
  a.click()
  a.remove()
}
