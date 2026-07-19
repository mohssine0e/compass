import { useCallback, useRef, useState } from 'react'
import {
  applyRestructure,
  proposeRestructure,
  recheckResurfacing,
  respondResurfacing,
} from '../api'
import { useSpeechRecognition } from '../hooks/useSpeechRecognition'
import './ResurfacingScreen.css'

// Shown before capture when something stalled is worth an honest look (Phase 2).
// For a stalled roadmap, the options can also open a restructuring flow (Phase 4).
// For a done step due for spaced retrieval, it's a recheck instead (Phase 8).
export default function ResurfacingScreen({ prompt, onDone }) {
  return prompt.mode === 'recheck' ? (
    <RecheckView prompt={prompt} onDone={onDone} />
  ) : (
    <ResurfaceView prompt={prompt} onDone={onDone} />
  )
}

function ResurfaceView({ prompt, onDone }) {
  const { entry, question, options } = prompt
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  // null | { kind, loading } | { kind, targetStepId, steps }        (break_down)
  //      | { kind, targetStepId, prerequisite, why }                (add_prerequisite)
  const [restructure, setRestructure] = useState(null)
  const textareaRef = useRef(null)

  const appendSpokenText = useCallback((chunk) => {
    const clean = chunk.trim()
    if (clean) setText((prev) => (prev ? prev.trimEnd() + ' ' : '') + clean)
  }, [])
  const speech = useSpeechRecognition({ onFinalText: appendSpokenText })

  async function respond(option, freeText) {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      if (speech.listening) speech.stop()
      await respondResurfacing(entry.id, { option, text: freeText })
      onDone()
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  async function startRestructure(kind) {
    if (busy) return
    setError(null)
    setRestructure({ kind, loading: true })
    try {
      const p = await proposeRestructure(entry.id, kind)
      if (p.kind === 'break_down') {
        setRestructure({
          kind,
          targetStepId: p.targetStepId,
          targetStepText: p.targetStepText,
          steps: p.steps && p.steps.length ? p.steps : [''],
        })
      } else {
        setRestructure({
          kind,
          targetStepId: p.targetStepId,
          targetStepText: p.targetStepText,
          prerequisite: p.prerequisite || '',
          why: p.why,
        })
      }
    } catch (err) {
      setError(err.message)
      setRestructure(null)
    }
  }

  async function applyChange() {
    if (busy || !restructure) return
    setBusy(true)
    setError(null)
    try {
      const body =
        restructure.kind === 'break_down'
          ? {
              kind: 'break_down',
              targetStepId: restructure.targetStepId,
              steps: restructure.steps.map((s) => s.trim()).filter(Boolean),
            }
          : {
              kind: 'add_prerequisite',
              targetStepId: restructure.targetStepId,
              prerequisite: (restructure.prerequisite || '').trim(),
            }
      await applyRestructure(entry.id, body)
      onDone()
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  const entryText = entry.content.title || entry.content.text
  // Quick options are plain answers except "something else" (that one opens the text box).
  const quickOptions = options.filter(
    (o) => o.action === 'respond' && o.value !== 'something_else'
  )
  const restructureOptions = options.filter((o) => o.action === 'restructure')

  // Reviewing a proposed change takes over the screen until it's applied or dismissed.
  if (restructure) {
    return (
      <RestructureReview
        entryText={entryText}
        restructure={restructure}
        setRestructure={setRestructure}
        busy={busy}
        error={error}
        onApply={applyChange}
        onCancel={() => {
          setRestructure(null)
          setError(null)
        }}
      />
    )
  }

  return (
    <div className="resurface">
      <p className="resurface-context">On your mind a while ago:</p>
      <p className="resurface-entry">{entryText}</p>

      <h1 className="resurface-question">{question}</h1>

      <div className="resurface-options">
        {quickOptions.map((o) => (
          <button
            key={o.value}
            className="resurface-option"
            disabled={busy}
            onClick={() => respond(o.value)}
          >
            {o.label}
          </button>
        ))}
      </div>

      {restructureOptions.length > 0 && (
        <div className="resurface-restructure-options">
          <span className="resurface-restructure-lead">or change the plan:</span>
          {restructureOptions.map((o) => (
            <button
              key={o.value}
              className="resurface-restructure-btn"
              disabled={busy}
              onClick={() => startRestructure(o.value)}
            >
              {o.label}
            </button>
          ))}
        </div>
      )}

      <div className="resurface-say">
        <textarea
          ref={textareaRef}
          className="resurface-input"
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={speech.listening ? 'Listening…' : 'or say it in your own words…'}
          rows={2}
        />
        {speech.listening && speech.interim && (
          <p className="resurface-interim">{speech.interim}</p>
        )}
        <div className="resurface-say-actions">
          {speech.supported && (
            <button
              type="button"
              className={'resurface-mic' + (speech.listening ? ' is-active' : '')}
              onClick={speech.toggle}
              aria-pressed={speech.listening}
              aria-label={speech.listening ? 'Stop speaking' : 'Speak your answer'}
              title={speech.listening ? 'Stop speaking' : 'Speak your answer'}
            >
              <MicIcon />
            </button>
          )}
          <button
            className="resurface-respond"
            disabled={busy || text.trim().length === 0}
            onClick={() => respond('something_else', text.trim())}
          >
            Respond
          </button>
        </div>
      </div>

      {error && <p className="resurface-error">{error}</p>}

      <button
        className="resurface-skip"
        disabled={busy}
        onClick={() => respond('skip')}
      >
        Skip for now
      </button>
    </div>
  )
}

// Review, edit, and approve a proposed restructuring before anything is applied (Phase 4).
function RestructureReview({
  entryText,
  restructure,
  setRestructure,
  busy,
  error,
  onApply,
  onCancel,
}) {
  if (restructure.loading) {
    return (
      <div className="resurface">
        <p className="resurface-entry">{entryText}</p>
        <p className="resurface-restructure-lead">Thinking it through…</p>
      </div>
    )
  }

  const isBreakDown = restructure.kind === 'break_down'
  const cleanSteps = (restructure.steps || []).map((s) => s.trim()).filter(Boolean)
  const canApply = isBreakDown
    ? cleanSteps.length > 0 && !busy
    : (restructure.prerequisite || '').trim().length > 0 && !busy

  function setStep(i, value) {
    setRestructure((prev) => ({
      ...prev,
      steps: prev.steps.map((s, j) => (j === i ? value : s)),
    }))
  }
  function addStep() {
    setRestructure((prev) => ({ ...prev, steps: [...prev.steps, ''] }))
  }
  function removeStep(i) {
    setRestructure((prev) => ({
      ...prev,
      steps: prev.steps.length > 1 ? prev.steps.filter((_, j) => j !== i) : prev.steps,
    }))
  }

  return (
    <div className="resurface resurface-restructure">
      <p className="resurface-context">The step you're on:</p>
      <p className="resurface-entry">{restructure.targetStepText}</p>

      {isBreakDown ? (
        <>
          <p className="resurface-restructure-lead">
            Break it into smaller steps. Edit anything before you keep it.
          </p>
          <div className="resurface-steps">
            {restructure.steps.map((step, i) => (
              <div className="step-row" key={i}>
                <span className="step-index">{i + 1}</span>
                <input
                  className="step-input"
                  value={step}
                  onChange={(e) => setStep(i, e.target.value)}
                  placeholder={`Step ${i + 1}`}
                />
                <button
                  type="button"
                  className="step-remove"
                  onClick={() => removeStep(i)}
                  aria-label={`Remove step ${i + 1}`}
                  disabled={restructure.steps.length <= 1}
                >
                  ×
                </button>
              </div>
            ))}
            <button type="button" className="btn-ghost step-add" onClick={addStep}>
              + Add step
            </button>
          </div>
        </>
      ) : (
        <>
          {restructure.why && <p className="resurface-restructure-why">{restructure.why}</p>}
          <p className="resurface-restructure-lead">Do this first. Edit it before you keep it.</p>
          <input
            className="step-input resurface-prereq-input"
            value={restructure.prerequisite}
            onChange={(e) =>
              setRestructure((prev) => ({ ...prev, prerequisite: e.target.value }))
            }
            placeholder="Prerequisite step"
            autoFocus
          />
        </>
      )}

      {error && <p className="resurface-error">{error}</p>}

      <div className="resurface-restructure-actions">
        <button className="btn-ghost" onClick={onCancel} disabled={busy}>
          Never mind
        </button>
        <button className="btn-primary" onClick={onApply} disabled={!canApply}>
          {busy ? 'Applying…' : 'Apply'}
        </button>
      </div>
    </div>
  )
}

// A spaced recheck of a done step (Phase 8): answer the check again to reinforce it. Passing
// or missing, the step stays done — a miss just brings the next recheck sooner and names the gap.
function RecheckView({ prompt, onDone }) {
  const { entry, question } = prompt
  const [answer, setAnswer] = useState('')
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  async function submit() {
    if (busy || !answer.trim()) return
    setBusy(true)
    setError(null)
    try {
      setResult(await recheckResurfacing(entry.id, answer.trim()))
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  async function skip() {
    if (busy) return
    setBusy(true)
    try {
      await respondResurfacing(entry.id, { option: 'skip' })
    } catch {
      // best-effort; move on either way
    }
    onDone()
  }

  return (
    <div className="resurface">
      <p className="resurface-context">You learned this a while ago — does it still hold up?</p>
      <p className="resurface-entry">{entry.content.text}</p>

      {result ? (
        <>
          <p className={'recheck-verdict ' + (result.passed ? 'is-pass' : 'is-miss')}>
            {result.passed ? 'Still solid.' : 'Worth another look.'}
          </p>
          {!result.passed && result.gap && <p className="verify-gap">{result.gap}</p>}
          <button className="btn-primary recheck-done" onClick={onDone}>
            Done
          </button>
        </>
      ) : (
        <>
          <h1 className="resurface-question">{question}</h1>
          <textarea
            className="resurface-input recheck-answer"
            value={answer}
            onChange={(e) => setAnswer(e.target.value)}
            placeholder="Answer from memory…"
            rows={3}
            autoFocus
          />
          {error && <p className="resurface-error">{error}</p>}
          <div className="recheck-actions">
            <button className="resurface-skip" disabled={busy} onClick={skip}>
              Skip for now
            </button>
            <button className="btn-primary" disabled={busy || !answer.trim()} onClick={submit}>
              {busy ? 'Checking…' : 'Check'}
            </button>
          </div>
        </>
      )}
    </div>
  )
}

function MicIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <rect x="9" y="3" width="6" height="11" rx="3" />
      <path d="M5 11a7 7 0 0 0 14 0" />
      <line x1="12" y1="18" x2="12" y2="21" />
    </svg>
  )
}
