import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import UnifiedIntakeScreen from '../UnifiedIntakeScreen'
import { classifyIntent, createEntry } from '../../api'

vi.mock('../../api', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, classifyIntent: vi.fn(), createEntry: vi.fn() }
})

// RB-6.2: ambient classification fires ~700ms after typing stops, guarded by a request-id so a
// stale response for edited-since text is never shown. Both properties are timing-dependent and
// invisible from reading the component — exactly what V3-1.3 asks to cover.
describe('UnifiedIntakeScreen ambient classification debounce', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    classifyIntent.mockReset()
    createEntry.mockReset()
    createEntry.mockResolvedValue({ id: 1, acknowledgment: null })
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  function renderScreen() {
    return render(
      <UnifiedIntakeScreen onOpenRoadmap={() => {}} onOpenRoadmapBuilder={() => {}} onOpenAll={() => {}} />,
    )
  }

  it('does not classify immediately on keystroke', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderScreen()

    await user.type(screen.getByPlaceholderText(/capture a thought/i), 'l')

    expect(classifyIntent).not.toHaveBeenCalled()
  })

  it('classifies once, ~700ms after typing stops', async () => {
    classifyIntent.mockResolvedValue({ intent: 'IDEA', confidence: 0.9 })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderScreen()

    await user.type(screen.getByPlaceholderText(/capture a thought/i), 'learn rust')
    expect(classifyIntent).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(700)

    expect(classifyIntent).toHaveBeenCalledTimes(1)
    expect(classifyIntent).toHaveBeenCalledWith('learn rust')
  })

  it('continued typing resets the debounce rather than firing on every keystroke', async () => {
    classifyIntent.mockResolvedValue({ intent: 'IDEA', confidence: 0.9 })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderScreen()

    const box = screen.getByPlaceholderText(/capture a thought/i)
    await user.type(box, 'learn')
    await vi.advanceTimersByTimeAsync(400) // short of the 700ms threshold
    await user.type(box, ' rust')
    await vi.advanceTimersByTimeAsync(400) // still short, measured from the latest keystroke

    expect(classifyIntent).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(300) // now 700ms since the last keystroke

    expect(classifyIntent).toHaveBeenCalledTimes(1)
    expect(classifyIntent).toHaveBeenCalledWith('learn rust')
  })

  it('a stale response for since-edited text is discarded, not shown', async () => {
    let resolveFirst
    classifyIntent.mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve }))
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderScreen()

    const box = screen.getByPlaceholderText(/capture a thought/i)
    await user.type(box, 'learn rust')
    await vi.advanceTimersByTimeAsync(700) // first call fires and is now in flight

    // Edit again before the first call resolves, and let its own debounced call fire too.
    classifyIntent.mockResolvedValueOnce({ intent: 'TASK', confidence: 0.8 })
    await user.type(box, ' programming')
    await vi.advanceTimersByTimeAsync(700)

    expect(await screen.findByText(/task/i)).toBeInTheDocument()

    // The stale first call finally resolves — it must not clobber the newer, already-shown result.
    resolveFirst({ intent: 'IDEA', confidence: 0.9 })
    await vi.runAllTimersAsync()

    expect(screen.getByText(/task/i)).toBeInTheDocument()
  })

  it('clearing the text back to empty suppresses the ambient label', async () => {
    classifyIntent.mockResolvedValue({ intent: 'IDEA', confidence: 0.9 })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderScreen()

    const box = screen.getByPlaceholderText(/capture a thought/i)
    await user.type(box, 'learn rust')
    await vi.advanceTimersByTimeAsync(700)
    expect(await screen.findByText(/idea/i)).toBeInTheDocument()

    await user.clear(box)

    expect(screen.queryByText(/idea/i)).not.toBeInTheDocument()
  })
})

// V3-10: submitting used to re-run classifyIntent unconditionally even when the ambient
// debounce had already classified the exact same text moments earlier — a second AI round trip
// on the critical path of every capture. Reusing that result (when it's still fresh) removes
// it entirely; a hard 3s deadline covers the remaining case where no fresh ambient result exists.
describe('UnifiedIntakeScreen submit-time classification', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    classifyIntent.mockReset()
    createEntry.mockReset()
    createEntry.mockResolvedValue({ id: 1, acknowledgment: null })
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  function renderScreen() {
    return render(
      <UnifiedIntakeScreen onOpenRoadmap={() => {}} onOpenRoadmapBuilder={() => {}} onOpenAll={() => {}} />,
    )
  }

  it('reuses the fresh ambient result on submit instead of classifying a second time', async () => {
    classifyIntent.mockResolvedValue({ intent: 'IDEA', confidence: 0.9 })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderScreen()

    const box = screen.getByPlaceholderText(/capture a thought/i)
    await user.type(box, 'a quick idea')
    await vi.advanceTimersByTimeAsync(700) // ambient classification settles
    expect(classifyIntent).toHaveBeenCalledTimes(1)
    // Wait for the ambient render to actually commit — otherwise Enter can be dispatched to a
    // stale closure that still sees the pre-classification `ambient` state.
    expect(await screen.findByText(/idea/i)).toBeInTheDocument()

    await user.type(box, '{enter}')
    await vi.waitFor(() => expect(createEntry).toHaveBeenCalled())

    // Submit reused the ambient result — no second call for the same text.
    expect(classifyIntent).toHaveBeenCalledTimes(1)
    expect(createEntry).toHaveBeenCalledWith(expect.objectContaining({ text: 'a quick idea' }))
  })

  it('gives up on a hanging classify call after 3s and falls back to IDEA', async () => {
    // Never resolves on its own — only reacts to the component's own abort signal, simulating a
    // provider that's still inside its own much longer internal timeout.
    classifyIntent.mockImplementation(
      (_input, options) =>
        new Promise((_resolve, reject) => {
          options?.signal?.addEventListener('abort', () => {
            const err = new Error('aborted')
            err.name = 'AbortError'
            reject(err)
          })
        }),
    )
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderScreen()

    const box = screen.getByPlaceholderText(/capture a thought/i)
    // Submit immediately (no pause for the ambient debounce), so submit() must classify itself.
    await user.type(box, 'submitted right away{enter}')

    expect(createEntry).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(3000)

    await vi.waitFor(() => expect(createEntry).toHaveBeenCalled())
    expect(createEntry).toHaveBeenCalledWith(expect.objectContaining({ text: 'submitted right away' }))
  })
})
