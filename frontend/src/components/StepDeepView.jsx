import { useEffect, useRef, useState } from 'react'
import { endSession, getStepCovers, patchEntry, startSession } from '../api'
import './StepDeepView.css'

// The optional deep view for a roadmap step (Phase 7.5): description, effort, what it covers,
// attached resources, your own notes, and lightweight session time-tracking. The roadmap list
// works fine without ever opening this — low friction preserved.
const WEIGHT_LABEL = { small: 'small', medium: 'medium', large: 'large' }

export default function StepDeepView({ step, onClose, onChanged }) {
  const content = step.content || {}
  const [covers, setCovers] = useState(content.covers || null)
  const [loadingCovers, setLoadingCovers] = useState(false)
  const [notes, setNotes] = useState(content.notes || '')
  const [savedNotes, setSavedNotes] = useState(content.notes || '')
  const [savingNotes, setSavingNotes] = useState(false)
  const [sessionOpen, setSessionOpen] = useState(hasOpenSession(content))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const notesTimer = useRef(null)

  // Fetch "what this covers" once when opened, if not already cached on the step.
  useEffect(() => {
    if (covers) return
    let alive = true
    setLoadingCovers(true)
    getStepCovers(step.id)
      .then((res) => alive && setCovers(res.covers || []))
      .catch(() => alive && setCovers([])) // unavailable: just show nothing, no error noise
      .finally(() => alive && setLoadingCovers(false))
    return () => {
      alive = false
    }
  }, [step.id, covers])

  // Debounced notes autosave.
  function onNotesChange(value) {
    setNotes(value)
    clearTimeout(notesTimer.current)
    notesTimer.current = setTimeout(() => saveNotes(value), 800)
  }
  async function saveNotes(value) {
    if (value === savedNotes) return
    setSavingNotes(true)
    try {
      await patchEntry(step.id, { notes: value })
      setSavedNotes(value)
      onChanged?.()
    } catch (err) {
      setError(err.message)
    } finally {
      setSavingNotes(false)
    }
  }

  async function toggleSession() {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      if (sessionOpen) {
        await endSession(step.id, {})
        setSessionOpen(false)
      } else {
        await startSession(step.id)
        setSessionOpen(true)
      }
      onChanged?.()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  const resources = content.resources || []
  const sessions = (content.sessionHistory || []).filter((s) => s.durationMinutes != null)
  const totalMinutes = sessions.reduce((sum, s) => sum + (s.durationMinutes || 0), 0)

  return (
    <div className="deep-overlay" onClick={onClose}>
      <div className="deep-panel" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <button className="deep-close" onClick={onClose} aria-label="Close">
          ×
        </button>

        <h2 className="deep-title">{content.text}</h2>
        <div className="deep-meta">
          {content.kind && <span className={`gen-badge kind-${content.kind}`}>{content.kind}</span>}
          {content.weight && (
            <span className="gen-badge">{WEIGHT_LABEL[content.weight] || content.weight} effort</span>
          )}
          {totalMinutes > 0 && <span className="deep-time">{totalMinutes} min invested</span>}
        </div>

        {content.rationale && <p className="deep-rationale">{content.rationale}</p>}

        <section className="deep-section">
          <h3 className="deep-section-title">What this covers</h3>
          {loadingCovers && <p className="deep-faint">Working it out…</p>}
          {covers && covers.length > 0 ? (
            <ul className="deep-covers">
              {covers.map((c, i) => (
                <li key={i}>{c}</li>
              ))}
            </ul>
          ) : (
            !loadingCovers && <p className="deep-faint">Nothing to add here.</p>
          )}
        </section>

        <section className="deep-section">
          <h3 className="deep-section-title">Resources</h3>
          {resources.length === 0 ? (
            <p className="deep-faint">No resources on this step.</p>
          ) : (
            <ul className="deep-resources">
              {resources.map((r) => (
                <li key={r.id || r.url} className="deep-resource">
                  <a href={r.url} target="_blank" rel="noreferrer" className="deep-resource-title">
                    {r.title}
                  </a>
                  <span className="deep-resource-meta">
                    {r.format && <span className="gen-badge">{r.format}</span>}
                    {r.estimatedTime && <span className="deep-faint">{r.estimatedTime}</span>}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="deep-section">
          <h3 className="deep-section-title">Your notes</h3>
          <textarea
            className="deep-notes"
            value={notes}
            onChange={(e) => onNotesChange(e.target.value)}
            placeholder="Anything worth remembering about this step…"
            rows={3}
          />
          <span className="deep-notes-status">
            {savingNotes ? 'Saving…' : notes !== savedNotes ? '' : notes ? 'Saved.' : ''}
          </span>
        </section>

        {error && <p className="deep-error">{error}</p>}

        <div className="deep-actions">
          <button
            className={'btn-primary' + (sessionOpen ? ' is-active-session' : '')}
            onClick={toggleSession}
            disabled={busy}
          >
            {busy ? '…' : sessionOpen ? 'End session' : 'Start session'}
          </button>
        </div>
      </div>
    </div>
  )
}

function hasOpenSession(content) {
  const history = content.sessionHistory || []
  const last = history[history.length - 1]
  return !!last && last.durationMinutes == null
}
