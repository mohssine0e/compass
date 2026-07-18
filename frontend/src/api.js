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
