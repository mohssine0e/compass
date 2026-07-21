// Thin API client. Backend lives under /api (proxied to Spring in dev).

const BASE = '/api'

async function request(path, options = {}) {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
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
    const job = await request(`/roadmaps/generate/jobs/${jobId}`)
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

/** Accept a module's expanded steps. `draftSteps` is the same shape as roadmap creation. */
export function addModuleSteps(roadmapId, moduleId, draftSteps) {
  return request(`/roadmaps/${roadmapId}/modules/${moduleId}/steps`, {
    method: 'POST',
    body: JSON.stringify({ draftSteps }),
  })
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

/** Insert an accepted new module. `position` is 0-based; omit to append. */
export function insertModule(roadmapId, title, scope, position) {
  return request(`/roadmaps/${roadmapId}/modules`, {
    method: 'POST',
    body: JSON.stringify({ title, scope, position }),
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

/** Generate a verification check for a step (Phase 8). Returns { question }. */
export function getStepCheck(stepId) {
  return request(`/verification/steps/${stepId}/check`, { method: 'POST' })
}

/** Answer a step's check. Returns { passed, gap }; on pass the step is marked done. */
export function verifyStep(stepId, answer) {
  return request(`/verification/steps/${stepId}/verify`, {
    method: 'POST',
    body: JSON.stringify({ answer }),
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
