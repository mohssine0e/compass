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

/** Create a roadmap. `payload` is { title, notes?, steps: string[] }. */
export function createRoadmap(payload) {
  return request('/roadmaps', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/**
 * One turn of AI roadmap drafting. Call with `{ goal }` to get clarifying questions back,
 * then with `{ goal, clarifications: [{ question, answer }] }` to get an editable proposal.
 * Throws (503) when drafting is unavailable — fall back to the manual form.
 */
export function generateRoadmap(payload) {
  return request('/roadmaps/generate', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/** All roadmaps with their steps and progress, newest first. */
export function listRoadmaps() {
  return request('/roadmaps')
}

/** One roadmap with its ordered steps and progress. */
export function getRoadmap(id) {
  return request(`/roadmaps/${id}`)
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

/** The one thing to resurface before capture, or null when nothing qualifies. */
export function getNextResurfacing() {
  return request('/resurfacing/next')
}

/** Record a response to a resurfacing prompt. `body` is { option, text? }. */
export function respondResurfacing(id, body) {
  return request(`/resurfacing/${id}/respond`, {
    method: 'POST',
    body: JSON.stringify(body),
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
