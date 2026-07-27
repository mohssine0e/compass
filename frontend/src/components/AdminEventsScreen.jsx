import { useCallback, useEffect, useState } from 'react'
import { getAdminEvents, getProviderHealth } from '../api'
import { Chip } from './ui'
import './AdminEventsScreen.css'

// Operational window into recent system events (Phase 5): a plain list, most recent first,
// filterable by source/severity — nothing fancier than the plain-list views elsewhere.
// Filters are Chip toggles (Phase 23), matching the pattern used in Everything/Profile — an
// empty string means "all"; clicking the already-selected chip clears back to it.
const SOURCES = ['ai_provider', 'system', 'founder']
const SEVERITIES = ['info', 'warning', 'error']

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
      <ProviderHealthPanel />

      <div className="events-head">
        <h1 className="screen-title">Events</h1>
        <div className="events-filters">
          <FilterChips label="source" value={source} onChange={setSource} options={SOURCES} />
          <FilterChips label="severity" value={severity} onChange={setSeverity} options={SEVERITIES} />
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

/**
 * Which AI providers are actually working right now (V3-2.3). Sits above the event list because
 * it answers the question the events below usually raise — "is a provider down, or is it me?" —
 * without opening the Groq, Google, and NVIDIA consoles separately to find out.
 */
function ProviderHealthPanel() {
  const [providers, setProviders] = useState(null)

  useEffect(() => {
    let alive = true
    getProviderHealth()
      .then((p) => alive && setProviders(p))
      .catch(() => alive && setProviders([]))
    return () => {
      alive = false
    }
  }, [])

  if (!providers || providers.length === 0) return null

  return (
    <section className="providers">
      <h2 className="providers-title">Providers</h2>
      <ul className="providers-list">
        {providers.map((p) => (
          <li key={`${p.tier}-${p.name}-${p.model}`} className={'provider-row ' + statusClass(p)}>
            <span className="provider-name">{p.name}</span>
            <span className="provider-tier">{p.tier}</span>
            <span className="provider-state">{stateLabel(p)}</span>
            <span className="provider-timing">
              {p.avgDurationMs != null ? `${Math.round(p.avgDurationMs)}ms avg` : ''}
            </span>
            <span className="provider-counts">
              {p.successes > 0 || p.failures > 0 ? `${p.successes} ok · ${p.failures} failed` : ''}
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}

function statusClass(p) {
  if (!p.configured) return 'is-unconfigured'
  if (p.coolingDownForSeconds != null) return 'is-benched'
  return 'is-ok'
}

function stateLabel(p) {
  if (!p.configured) return 'no key set'
  if (p.coolingDownForSeconds != null) {
    const mins = Math.ceil(p.coolingDownForSeconds / 60)
    return `benched ${mins}m — ${(p.lastFailureKind || '').toLowerCase().replace('_', ' ')}`
  }
  if (p.lastSuccessAt) return 'working'
  return 'not called yet'
}

function FilterChips({ label, value, onChange, options }) {
  return (
    <div className="events-filter">
      <span className="events-filter-label">{label}</span>
      <div className="events-filter-chips">
        {options.map((o) => (
          <Chip
            key={o}
            toggle
            pressed={value === o}
            onClick={() => onChange(value === o ? '' : o)}
          >
            {o}
          </Chip>
        ))}
      </div>
    </div>
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
