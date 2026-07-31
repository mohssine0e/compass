import { useEffect, useRef, useState } from 'react'
import { getNotificationHistory } from '../../api'
import { IconBell } from './icons'

function relativeTime(iso) {
  const seconds = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000))
  if (seconds < 60) return 'just now'
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  return `${Math.round(hours / 24)}d ago`
}

/**
 * A bell button opening a read-only list of past notifications (V3-10) — separate from the
 * active toast stack in `Toast.jsx`. A toast is transient (auto-dismisses, capped at 10 visible
 * at once); this is "what did I miss," fetched fresh from `GET /notifications/history` each time
 * it's opened rather than kept in sync continuously, since it's a look-back, not a live feed.
 */
export default function NotificationHistory() {
  const [open, setOpen] = useState(false)
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(false)
  const ref = useRef(null)

  useEffect(() => {
    if (!open) return undefined
    const onDown = (e) => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false)
    }
    const onKey = (e) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      window.removeEventListener('keydown', onKey)
    }
  }, [open])

  function toggle() {
    setOpen((prev) => {
      const next = !prev
      if (next) {
        setLoading(true)
        getNotificationHistory(20)
          .then(setItems)
          .catch(() => setItems([]))
          .finally(() => setLoading(false))
      }
      return next
    })
  }

  return (
    <span className="ui-notif-history" ref={ref}>
      <button
        className="ui-notif-history__trigger"
        onClick={toggle}
        aria-haspopup="true"
        aria-expanded={open}
        aria-label="Past notifications"
      >
        <IconBell />
      </button>
      {open && (
        <span className="ui-notif-history__panel" role="region" aria-label="Past notifications">
          {loading && <span className="ui-notif-history__empty">Loading…</span>}
          {!loading && items.length === 0 && (
            <span className="ui-notif-history__empty">Nothing yet.</span>
          )}
          {!loading &&
            items.map((n) => (
              <span key={n.id} className={`ui-notif-history__item ui-notif-history__item--${n.tone}`}>
                <span className="ui-notif-history__message">
                  {n.count > 1 ? `${n.message} (×${n.count})` : n.message}
                </span>
                <span className="ui-notif-history__time">{relativeTime(n.createdAt)}</span>
              </span>
            ))}
        </span>
      )}
    </span>
  )
}
