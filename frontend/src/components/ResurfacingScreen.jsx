import { useCallback, useRef, useState } from 'react'
import {
  applyRestructure,
  proposeRestructure,
  recheckResurfacing,
  respondResurfacing,
  suggestResources,
} from '../api'
import { useSpeechRecognition } from '../hooks/useSpeechRecognition'
import StepProposalEditor, { fromProposedSteps, toDraftSteps } from './StepProposalEditor'
import { Button, Card } from './ui'
import './ResurfacingScreen.css'

let rcidCounter = 0
function nextRcid() {
  rcidCounter += 1
  return rcidCounter
}

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
  // True while resources for a break_down proposal are being found as a separate follow-up call
  // — the substep structure is shown immediately, resources fill in moments later.
  const [resourcesPending, setResourcesPending] = useState(false)
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
        const editorSteps = fromProposedSteps(p.steps)
        setRestructure({
          kind,
          targetStepId: p.targetStepId,
          targetStepText: p.targetStepText,
          steps: editorSteps,
        })
        fetchResourcesFor(editorSteps, p.roadmapId, p.targetStepText)
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

  // The break_down proposal's substeps are already shown; find their resources as a quick
  // follow-up (POST /resources/suggest) instead of making the founder wait for both. Matches by
  // cid, not array position, so it's still correct even if the founder edits/removes a step
  // while resources are still being found.
  async function fetchResourcesFor(stepsSnapshot, roadmapId, scope) {
    const cids = stepsSnapshot.map((s) => s.cid)
    const stepTexts = stepsSnapshot.map((s) => s.text)
    if (!stepTexts.length) return
    setResourcesPending(true)
    try {
      const resourceLists = await suggestResources({ scope: scope || '', stepTexts, roadmapId })
      setRestructure((prev) =>
        prev && prev.steps
          ? {
              ...prev,
              steps: prev.steps.map((s) => {
                const idx = cids.indexOf(s.cid)
                const found = idx >= 0 ? resourceLists[idx] : null
                return found && found.length
                  ? { ...s, resources: found.map((r) => ({ rcid: nextRcid(), ...r })) }
                  : s
              }),
            }
          : prev
      )
    } catch {
      // Best-effort — a failed resources fetch just leaves steps without suggestions.
    } finally {
      setResourcesPending(false)
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
              draftSteps: toDraftSteps(restructure.steps),
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

      {prompt.note && <p className="resurface-history">{prompt.note}</p>}

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
  const cleanSteps = (restructure.steps || []).filter((s) => s.text.trim())
  const canApply = isBreakDown
    ? cleanSteps.length > 0 && !busy
    : (restructure.prerequisite || '').trim().length > 0 && !busy

  return (
    <div className="resurface resurface-restructure">
      <p className="resurface-context">The step you're on:</p>
      <p className="resurface-entry">{restructure.targetStepText}</p>

      <Card className="resurface-proposal-card">
        {isBreakDown ? (
          <>
            <p className="resurface-restructure-lead">
              Break it into smaller steps. Edit anything before you keep it.
            </p>
            <StepProposalEditor
              steps={restructure.steps}
              onChange={(next) => setRestructure((prev) => ({ ...prev, steps: next }))}
              resourcesPending={resourcesPending}
            />
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
      </Card>

      {error && <p className="resurface-error">{error}</p>}

      <div className="resurface-restructure-actions">
        <Button variant="ghost" onClick={onCancel} disabled={busy}>
          Never mind
        </Button>
        <Button variant="primary" onClick={onApply} disabled={!canApply}>
          {busy ? 'Applying…' : 'Apply'}
        </Button>
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
          <Button variant="primary" className="recheck-done" onClick={onDone}>
            Done
          </Button>
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
            <Button variant="primary" disabled={busy || !answer.trim()} onClick={submit}>
              {busy ? 'Checking…' : 'Check'}
            </Button>
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
