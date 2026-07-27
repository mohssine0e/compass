import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TimeoutError, getAdminEvents, listEntries, patchEntry, request } from '../api'

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  }
}

// A fetch stub whose promise only settles when the request's AbortSignal fires — stands in for
// a hung connection, so the timeout/abort branches can be exercised without a real network call.
function abortableFetch() {
  return vi.fn((_url, opts) => new Promise((_resolve, reject) => {
    opts.signal.addEventListener('abort', () => {
      reject(Object.assign(new Error('The operation was aborted'), { name: 'AbortError' }))
    })
  }))
}

describe('request()', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('resolves with the parsed JSON body on a 200', async () => {
    fetch.mockResolvedValueOnce(jsonResponse([{ id: 1 }]))

    const result = await listEntries()

    expect(result).toEqual([{ id: 1 }])
    expect(fetch).toHaveBeenCalledWith('/api/entries', expect.objectContaining({
      headers: { 'Content-Type': 'application/json' },
    }))
  })

  it('a non-ok response throws an Error using the ProblemDetail "detail" field', async () => {
    fetch.mockResolvedValueOnce(jsonResponse({ detail: 'An entry needs some text to capture.' }, 400))

    await expect(listEntries()).rejects.toThrow('An entry needs some text to capture.')
  })

  it('a non-ok response with a non-JSON body falls back to a generic status message', async () => {
    fetch.mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: async () => {
        throw new Error('not json')
      },
    })

    await expect(listEntries()).rejects.toThrow('Request failed (500)')
  })

  it('a 204 response resolves to null rather than attempting to parse a body', async () => {
    fetch.mockResolvedValueOnce({ ok: true, status: 204, json: async () => { throw new Error('should not be called') } })

    await expect(patchEntry(1, { status: 'done' })).resolves.toBeNull()
  })

  it('PATCH sends the method and JSON-stringified body', async () => {
    fetch.mockResolvedValueOnce(jsonResponse({ id: 1, status: 'done' }))

    await patchEntry(1, { status: 'done' })

    expect(fetch).toHaveBeenCalledWith('/api/entries/1', expect.objectContaining({
      method: 'PATCH',
      body: JSON.stringify({ status: 'done' }),
    }))
  })

  it('an internal timeout surfaces as TimeoutError, not a raw AbortError', async () => {
    vi.useFakeTimers()
    fetch.mockImplementationOnce(abortableFetch())

    const pending = request('/roadmaps', { timeoutMs: 5_000 })
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError)
    await vi.advanceTimersByTimeAsync(5_000)

    await assertion
  })

  it('the timeout still fires even when the caller passed no options at all', async () => {
    vi.useFakeTimers()
    fetch.mockImplementationOnce(abortableFetch())

    const pending = listEntries()
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError)
    await vi.advanceTimersByTimeAsync(120_000) // the real default ceiling

    await assertion
  })

  it("the caller's own abort (e.g. component unmount) is NOT reported as a TimeoutError", async () => {
    fetch.mockImplementationOnce(abortableFetch())
    const callerController = new AbortController()

    const pending = request('/roadmaps/1', { signal: callerController.signal, timeoutMs: 5_000 })
    const assertion = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    callerController.abort()

    await assertion
    await expect(pending).rejects.not.toBeInstanceOf(TimeoutError)
  })

  it('a genuine network failure is retried once, transparently, and succeeds if the retry does', async () => {
    vi.useFakeTimers()
    fetch
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(jsonResponse([{ id: 1 }]))

    const pending = listEntries()
    await vi.advanceTimersByTimeAsync(400)

    await expect(pending).resolves.toEqual([{ id: 1 }])
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('a network failure that fails twice propagates as-is, unwrapped, after the one retry', async () => {
    vi.useFakeTimers()
    fetch.mockRejectedValue(new TypeError('Failed to fetch'))

    const pending = listEntries()
    const assertion = expect(pending).rejects.toThrow('Failed to fetch')
    await vi.advanceTimersByTimeAsync(400)

    await assertion
    expect(fetch).toHaveBeenCalledTimes(2) // the original attempt plus exactly one retry, not a loop
  })

  it('a non-ok HTTP response (4xx/5xx) is never retried — the server already answered', async () => {
    fetch.mockResolvedValueOnce(jsonResponse({ detail: 'bad request' }, 400))

    await expect(listEntries()).rejects.toThrow('bad request')

    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('two identical concurrent GETs share one in-flight request', async () => {
    let resolveFetch
    fetch.mockImplementationOnce(() => new Promise((resolve) => { resolveFetch = resolve }))

    const first = listEntries()
    const second = listEntries()
    resolveFetch(jsonResponse([{ id: 1 }]))

    await Promise.all([first, second])

    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('a GET after the first has resolved fires its own new request, not a stale shared one', async () => {
    fetch.mockResolvedValueOnce(jsonResponse([{ id: 1 }]))
    await listEntries()

    fetch.mockResolvedValueOnce(jsonResponse([{ id: 1 }, { id: 2 }]))
    const second = await listEntries()

    expect(fetch).toHaveBeenCalledTimes(2)
    expect(second).toEqual([{ id: 1 }, { id: 2 }])
  })

  it('a GET with its own abort signal is never folded into another caller\'s in-flight request', async () => {
    let resolveFirst
    fetch.mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve }))
    fetch.mockResolvedValueOnce(jsonResponse([{ id: 99 }]))

    const first = request('/entries') // no signal — eligible for dedup
    const second = request('/entries', { signal: new AbortController().signal }) // has one — must not share

    resolveFirst(jsonResponse([{ id: 1 }]))
    const [firstResult, secondResult] = await Promise.all([first, second])

    expect(fetch).toHaveBeenCalledTimes(2)
    expect(firstResult).toEqual([{ id: 1 }])
    expect(secondResult).toEqual([{ id: 99 }])
  })

  it('POST requests are never deduped, even to the same path', async () => {
    fetch.mockResolvedValue(jsonResponse({ id: 1 }))

    await Promise.all([patchEntry(1, { status: 'done' }), patchEntry(1, { status: 'done' })])

    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('getAdminEvents omits unset filters from the query string', async () => {
    fetch.mockResolvedValueOnce(jsonResponse([]))

    await getAdminEvents({})

    expect(fetch).toHaveBeenCalledWith('/api/admin/events', expect.anything())
  })

  it('getAdminEvents includes only the filters that were set', async () => {
    fetch.mockResolvedValueOnce(jsonResponse([]))

    await getAdminEvents({ source: 'ai_provider', limit: 20 })

    expect(fetch).toHaveBeenCalledWith('/api/admin/events?source=ai_provider&limit=20', expect.anything())
  })
})
