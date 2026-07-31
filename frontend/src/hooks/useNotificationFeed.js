import { useCallback, useRef, useState } from 'react'
import { pollNotifications } from '../api'
import { usePolling } from './usePolling'

const POLL_INTERVAL_MS = 3000

// A toast stack that just grows would eventually cover the screen if several background jobs
// land close together (e.g. a batch expansion finishing several modules at once). Capping at 10
// and dropping the oldest keeps it a stack, not a feed — anything older is still recoverable
// from the notification history panel (see useNotificationHistory), it just isn't held as an
// active toast anymore.
const MAX_VISIBLE_TOASTS = 10

/**
 * The global toast feed (V3-10): background work (a delayed capture acknowledgment, a roadmap
 * finishing generation, a module finishing drafting) delivers here instead of blocking the
 * request that started it. Meant to be mounted once, at the app root — not per-screen.
 */
export function useNotificationFeed() {
  const [toasts, setToasts] = useState([])
  const cursor = useRef(0)

  usePolling(
    () => pollNotifications(cursor.current),
    POLL_INTERVAL_MS,
    (received) => {
      if (!received.length) return
      cursor.current = received[received.length - 1].id
      // New ones are appended at the end; ToastStack renders that end first (column-reverse), so
      // this is "newest on top." Slicing from the end after appending is what drops the oldest
      // once the cap is exceeded, same effect whether one or several arrive in the same poll.
      setToasts((prev) => [...prev, ...received].slice(-MAX_VISIBLE_TOASTS))
    },
    [],
  )

  const dismiss = useCallback((id) => {
    setToasts((prev) => prev.filter((t) => t.id !== id))
  }, [])

  return { toasts, dismiss }
}
