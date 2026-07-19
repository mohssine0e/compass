import { useEffect, useRef, useState } from 'react'
import { endSession, explainText, getStepCovers, patchEntry, startSession } from '../api'
import './StepDeepView.css'

// Select-text help actions (Phase 8.5). Labels stay plain, never teacher-y.
const HELP_ACTIONS = [
  { action: 'explain', label: 'Explain' },
  { action: 'simplify', label: 'Simplify' },
  { action: 'concrete_example', label: 'Example' },
  { action: 'explain_with_background', label: 'Background' },
  { action: 'translate', label: 'Translate' },
]

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
  const panelRef = useRef(null)
  // Text-selection help (Phase 8.5): toolbar at the selection, then a result shown alongside.
  const [selection, setSelection] = useState(null) // { text, top, left }
  const [help, setHelp] = useState(null) // { action, text, loading, response, error }

  // Show the action toolbar when the user selects text inside the panel (but not in the notes).
  function onMouseUp(e) {
    if (e.target.tagName === 'TEXTAREA') return
    const sel = window.getSelection()
    const text = sel ? sel.toString().trim() : ''
    if (!text || !sel.rangeCount) {
      setSelection(null)
      return
    }
    const rect = sel.getRangeAt(0).getBoundingClientRect()
    if (!rect || rect.width === 0) {
      setSelection(null)
      return
    }
    setSelection({ text, top: rect.top - 8, left: rect.left + rect.width / 2 })
  }

  async function runHelp(action) {
    if (!selection) return
    const text = selection.text
    setSelection(null)
    window.getSelection()?.removeAllRanges()
    setHelp({ action, text, loading: true })
    try {
      const res = await explainText(text, {
        stepId: step.id,
        action,
        preferredDepth: 'brief',
        preferredLanguage: navigator.language,
      })
      setHelp({ action, text, response: res.response })
    } catch (err) {
      setHelp({ action, text, error: err.message })
    }
  }

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
      <div
        className="deep-panel"
        ref={panelRef}
        onClick={(e) => e.stopPropagation()}
        onMouseUp={onMouseUp}
        role="dialog"
        aria-modal="true"
      >
        <button className="deep-close" onClick={onClose} aria-label="Close">
          ×
        </button>

        <h2 className="deep-title">{content.text}</h2>

        {selection && (
          <div
            className="help-toolbar"
            style={{ top: selection.top, left: selection.left }}
            onMouseUp={(e) => e.stopPropagation()}
          >
            {HELP_ACTIONS.map((a) => (
              <button key={a.action} className="help-toolbar-btn" onClick={() => runHelp(a.action)}>
                {a.label}
              </button>
            ))}
          </div>
        )}

        {help && (
          <div className="help-result">
            <div className="help-result-head">
              <span className="help-result-action">{helpActionLabel(help.action)}</span>
              <button className="help-result-close" onClick={() => setHelp(null)} aria-label="Dismiss">
                ×
              </button>
            </div>
            <p className="help-result-quote">“{help.text}”</p>
            {help.loading && <p className="deep-faint">Thinking…</p>}
            {help.response && <p className="help-result-body">{help.response}</p>}
            {help.error && <p className="deep-error">{help.error}</p>}
          </div>
        )}
        <div className="deep-meta">
          {content.kind && <span className={`gen-badge kind-${content.kind}`}>{content.kind}</span>}
          {content.weight && (
            <span className="gen-badge">{WEIGHT_LABEL[content.weight] || content.weight} effort</span>
          )}
          {totalMinutes > 0 && <span className="deep-time">{totalMinutes} min invested</span>}
        </div>

        {content.rationale && <p className="deep-rationale">{content.rationale}</p>}
        <p className="deep-faint help-hint">Select any text below for help — explain, simplify, translate…</p>

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

function helpActionLabel(action) {
  const found = HELP_ACTIONS.find((a) => a.action === action)
  return found ? found.label : 'Help'
}
