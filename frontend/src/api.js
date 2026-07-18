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

/** Create a roadmap. `payload` is { title, notes?, steps: string[] }. */
export function createRoadmap(payload) {
  return request('/roadmaps', {
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
