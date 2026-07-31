import { useEffect } from 'react'

const AUTO_DISMISS_MS = 5000

/**
 * One notification, auto-dismissing after a few seconds or on click. `tone` is 'info' (brass —
 * the default, for anything that finished) or 'danger' (something that failed in the background).
 */
function Toast({ id, message, tone = 'info', count = 1, onDismiss }) {
  useEffect(() => {
    const t = setTimeout(() => onDismiss(id), AUTO_DISMISS_MS)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  return (
    <div
      className={`ui-toast ui-toast--${tone}`}
      role="status"
      aria-live="polite"
      onClick={() => onDismiss(id)}
    >
      {/* V4-1: the backend coalesces repeated (message, tone) pairs into one entry with a
          rising count instead of a wall of near-identical toasts — surface that count rather
          than silently dropping it. */}
      <span>{count > 1 ? `${message} (×${count})` : message}</span>
    </div>
  )
}

/**
 * The global notification feed's visible half (V3-10) — background work (a capture
 * acknowledgment, a roadmap finishing generation, a module finishing drafting) surfaces here
 * instead of the screen that started it. Renders nothing when there's nothing queued.
 */
export default function ToastStack({ toasts, onDismiss }) {
  if (!toasts.length) return null
  return (
    <div className="ui-toast-stack">
      {toasts.map((t) => (
        <Toast key={t.id} id={t.id} message={t.message} tone={t.tone} count={t.count} onDismiss={onDismiss} />
      ))}
    </div>
  )
}
