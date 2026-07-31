import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { useNotificationFeed } from '../useNotificationFeed'
import { pollNotifications } from '../../api'

vi.mock('../../api', () => ({ pollNotifications: vi.fn() }))

function notification(id, message = `n${id}`) {
  return { id, message, tone: 'info', createdAt: new Date().toISOString(), context: null }
}

// V3-10: a toast stack that only ever grows would eventually cover the screen if several
// background jobs land close together. Capped at 10, dropping the oldest, so it stays a stack
// rather than a feed — anything older is still recoverable from the history panel.
describe('useNotificationFeed', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    pollNotifications.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  it('caps the visible toast list at 10, keeping the most recent', async () => {
    pollNotifications.mockResolvedValueOnce(
      Array.from({ length: 12 }, (_, i) => notification(i + 1)),
    )
    pollNotifications.mockResolvedValue([])

    const { result } = renderHook(() => useNotificationFeed())

    await waitFor(() => expect(result.current.toasts).toHaveLength(10))
    // The oldest two (n1, n2) were dropped; the newest (n12) is kept, at the end of the array
    // (ToastStack renders that end first via column-reverse, i.e. "on top").
    expect(result.current.toasts.map((t) => t.id)).toEqual([3, 4, 5, 6, 7, 8, 9, 10, 11, 12])
  })

  it('drops exactly one more oldest toast as each additional notification arrives once at the cap', async () => {
    pollNotifications.mockResolvedValueOnce(
      Array.from({ length: 10 }, (_, i) => notification(i + 1)),
    )
    const { result } = renderHook(() => useNotificationFeed())
    await waitFor(() => expect(result.current.toasts).toHaveLength(10))

    pollNotifications.mockResolvedValueOnce([notification(11)])
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000)
    })

    expect(result.current.toasts).toHaveLength(10)
    expect(result.current.toasts.map((t) => t.id)).toEqual([2, 3, 4, 5, 6, 7, 8, 9, 10, 11])
  })

  it('dismiss removes exactly the given toast, regardless of cap', async () => {
    pollNotifications.mockResolvedValueOnce([notification(1), notification(2)])
    const { result } = renderHook(() => useNotificationFeed())
    await waitFor(() => expect(result.current.toasts).toHaveLength(2))

    act(() => result.current.dismiss(1))

    expect(result.current.toasts.map((t) => t.id)).toEqual([2])
  })
})
