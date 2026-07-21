import { useEffect, useRef, useState } from 'react'
import { endSession, explainText, getProfile, getStepCovers, patchEntry, startSession } from '../api'
import ReformulatePanel from './ReformulatePanel'
import { Badge, Button } from './ui'
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

export default function StepDeepView({ step, atMaxDepth = false, onClose, onChanged }) {
  const content = step.content || {}
  const [covers, setCovers] = useState(content.covers || null)
  const [loadingCovers, setLoadingCovers] = useState(false)
  const [notes, setNotes] = useState(content.notes || '')
  const [savedNotes, setSavedNotes] = useState(content.notes || '')
  const [savingNotes, setSavingNotes] = useState(false)
  const [sessionOpen, setSessionOpen] = useState(hasOpenSession(content))
  const [sessionStartedAt, setSessionStartedAt] = useState(openSessionStart(content))
  const [elapsed, setElapsed] = useState(0)
  const [ending, setEnding] = useState(false) // showing the "how was it?" feedback prompt
  const [post, setPost] = useState(null) // post-session next-action suggestion
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const notesTimer = useRef(null)
  // Preferred session length (Phase 15), if the profile has one — shapes the post-session nudge.
  const [sessionLengthPref, setSessionLengthPref] = useState(null)

  useEffect(() => {
    let alive = true
    getProfile()
      .then((p) => alive && setSessionLengthPref(p.learningPreferences?.sessionLength || null))
      .catch(() => {}) // best-effort; the nudge just falls back to its default wording
    return () => {
      alive = false
    }
  }, [])

  // Live timer while a session is running.
  useEffect(() => {
    if (!sessionOpen || !sessionStartedAt) return undefined
    const tick = () => setElapsed(Math.floor((Date.now() - sessionStartedAt) / 1000))
    tick()
    const id = setInterval(tick, 1000)
    return () => clearInterval(id)
  }, [sessionOpen, sessionStartedAt])
  const panelRef = useRef(null)
  // Text-selection help (Phase 8.5): toolbar at the selection, then a result shown alongside.
  const [selection, setSelection] = useState(null) // { text, top, left }
  const [help, setHelp] = useState(null) // { action, text, loading, response, error }
  const [reformulating, setReformulating] = useState(false)

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

  async function beginSession() {
    if (busy) return
    setBusy(true)
    setError(null)
    setPost(null)
    try {
      await startSession(step.id)
      setSessionOpen(true)
      setSessionStartedAt(Date.now())
      onChanged?.()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  // "Done for now" opens the feedback prompt; picking one ends the session with that feedback,
  // then suggests a next action (Phase 9). "Need help" opens the reformulate flow.
  async function finishSession(feedback, completed) {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await endSession(step.id, { userFeedback: feedback, completed })
      setSessionOpen(false)
      setEnding(false)
      setSessionStartedAt(null)
      setPost(nextActionFor(feedback, completed, sessionLengthPref))
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
          {content.kind && (
            <Badge tone={content.kind === 'project' ? 'brass' : 'default'}>{content.kind}</Badge>
          )}
          {content.weight && (
            <Badge>{WEIGHT_LABEL[content.weight] || content.weight} effort</Badge>
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
                    {r.format && <Badge>{r.format}</Badge>}
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

        {post && (
          <div className="session-post">
            <p className="session-post-line">{post}</p>
            <div className="session-post-actions">
              <Button variant="ghost" onClick={onClose}>
                Done
              </Button>
              <Button variant="ghost" onClick={() => setReformulating(true)}>
                Reformulate this
              </Button>
            </div>
          </div>
        )}

        {ending ? (
          <div className="session-feedback">
            <p className="session-feedback-q">How was it?</p>
            <div className="session-feedback-opts">
              <Button variant="ghost" onClick={() => finishSession('helpful', true)} disabled={busy}>
                Helpful — done
              </Button>
              <Button variant="ghost" onClick={() => finishSession('helpful', false)} disabled={busy}>
                Helpful — more to do
              </Button>
              <Button variant="ghost" onClick={() => finishSession('too_hard', false)} disabled={busy}>
                Too hard
              </Button>
            </div>
          </div>
        ) : (
          <div className="deep-actions">
            <button className="deep-toomuch" onClick={() => setReformulating(true)} disabled={busy}>
              This is too much
            </button>
            {sessionOpen ? (
              <span className="session-live">
                <span className="session-timer">{formatElapsed(elapsed)}</span>
                <Button variant="ghost" onClick={() => setReformulating(true)} disabled={busy}>
                  Need help
                </Button>
                <Button variant="primary" className="is-active-session" onClick={() => setEnding(true)} disabled={busy}>
                  Done for now
                </Button>
              </span>
            ) : (
              <Button variant="primary" onClick={beginSession} disabled={busy}>
                {busy ? '…' : 'Start session'}
              </Button>
            )}
          </div>
        )}
      </div>

      {reformulating && (
        <ReformulatePanel
          step={step}
          atMaxDepth={atMaxDepth}
          onClose={() => setReformulating(false)}
          onApplied={() => {
            setReformulating(false)
            onChanged?.()
            onClose()
          }}
        />
      )}
    </div>
  )
}

function hasOpenSession(content) {
  const history = content.sessionHistory || []
  const last = history[history.length - 1]
  return !!last && last.durationMinutes == null
}

function openSessionStart(content) {
  const history = content.sessionHistory || []
  const last = history[history.length - 1]
  if (last && last.durationMinutes == null && last.startedAt) {
    return new Date(last.startedAt).getTime()
  }
  return null
}

function formatElapsed(seconds) {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

// A plain, self-talk next-action nudge after a session — no pep talk (Phase 9). Shaped by the
// profile's preferred session length, if set (Phase 15): short-session people get pushed toward
// the break sooner, long-session people get the option to keep going left more open.
function nextActionFor(feedback, completed, sessionLengthPref) {
  if (completed) return 'That one holds. Next step, or stop here?'
  if (feedback === 'too_hard') return 'Too much in one go. Break it down, or come back to it fresh?'
  if (sessionLengthPref === 'short') return 'Good chunk done. Short sessions work for you — take the break.'
  if (sessionLengthPref === 'long') return "Good chunk done. Keep going if you're still in it, or stop here."
  return 'Good chunk done. Keep going, or take a break?'
}

function helpActionLabel(action) {
  const found = HELP_ACTIONS.find((a) => a.action === action)
  return found ? found.label : 'Help'
}
