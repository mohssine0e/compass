import { useCallback, useEffect, useState } from 'react'
import { getAdminEvents } from '../api'
import './AdminEventsScreen.css'

// Operational window into recent system events (Phase 5): a plain list, most recent first,
// filterable by source/severity — nothing fancier than the plain-list views elsewhere.
const SOURCES = ['', 'ai_provider', 'system']
const SEVERITIES = ['', 'info', 'warning', 'error']

export default function AdminEventsScreen() {
  const [events, setEvents] = useState(null)
  const [source, setSource] = useState('')
  const [severity, setSeverity] = useState('')
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      setEvents(await getAdminEvents({ source, severity }))
    } catch (err) {
      setError(err.message)
    }
  }, [source, severity])

  useEffect(() => {
    load()
  }, [load])

  return (
    <div className="events">
      <div className="events-head">
        <h1 className="screen-title">Events</h1>
        <div className="events-filters">
          <FilterSelect label="source" value={source} onChange={setSource} options={SOURCES} />
          <FilterSelect label="severity" value={severity} onChange={setSeverity} options={SEVERITIES} />
        </div>
      </div>

      {error && <p className="events-error">{error}</p>}

      {events && events.length === 0 && (
        <p className="events-empty">Nothing logged. Quiet is good.</p>
      )}

      {events && events.length > 0 && (
        <ul className="events-list">
          {events.map((e) => (
            <li key={e.id} className={`event-row sev-${e.severity}`}>
              <span className="event-when">{formatWhen(e.occurredAt)}</span>
              <span className="event-meta">
                <span className={`event-sev sev-${e.severity}`}>{e.severity}</span>
                <span className="event-source">{e.source}</span>
                <span className="event-category">{e.category}</span>
              </span>
              <span className="event-message">{e.message}</span>
              {e.context && Object.keys(e.context).length > 0 && (
                <span className="event-context">{formatContext(e.context)}</span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function FilterSelect({ label, value, onChange, options }) {
  return (
    <label className="events-filter">
      <span className="events-filter-label">{label}</span>
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        {options.map((o) => (
          <option key={o} value={o}>
            {o === '' ? 'all' : o}
          </option>
        ))}
      </select>
    </label>
  )
}

function formatWhen(iso) {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatContext(context) {
  return Object.entries(context)
    .map(([k, v]) => `${k}: ${v}`)
    .join(' · ')
}
