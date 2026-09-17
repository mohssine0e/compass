import { useCallback, useEffect, useState } from 'react'
import { applyReformulate, getStepCheck, verifyStep } from '../api'
import { Button, Chip, Modal, TextArea } from './ui'
import './VerifyModal.css'

// The check gate before a step counts as done (Phase 8, format variety added Phase 26). Fetches
// a fair question, takes the user's answer, and judges it — on a pass the step is marked done;
// on a miss it names the specific gap in the self-talk voice and stays open. "Mark done anyway"
// is an honest escape hatch (self-reported), so the gate never traps you.
//
// Three check formats, founder-overridable per check: multiple choice (auto-graded instantly),
// code challenge, and scenario — the latter two are free-text, judged the same way as before.
const FORMATS = [
  { value: 'multiple_choice', label: 'Multiple choice' },
  { value: 'code_challenge', label: 'Code challenge' },
  { value: 'scenario', label: 'Scenario' },
]

// A named gap can also carry a suggested prerequisite (Phase 20) — real evidence of what's
// actually missing, not a guess — that the founder accepts (reusing the existing add_prerequisite
// propose→approve→apply flow) or dismisses; never inserted silently.
export default function VerifyModal({ step, onClose, onPassed, onOverride, onChanged }) {
  const [format, setFormat] = useState(null)
  const [question, setQuestion] = useState(null)
  const [options, setOptions] = useState(null)
  const [selectedIndex, setSelectedIndex] = useState(null)
  const [loading, setLoading] = useState(true)
  const [answer, setAnswer] = useState('')
  const [gap, setGap] = useState(null)
  const [suggestedPrerequisite, setSuggestedPrerequisite] = useState(null)
  const [prerequisiteWhy, setPrerequisiteWhy] = useState(null)
  // null | 'accepted' | 'dismissed'
  const [prerequisiteHandled, setPrerequisiteHandled] = useState(null)
  const [busy, setBusy] = useState(false)
  const [confidence, setConfidence] = useState(null)
  const [error, setError] = useState(null)

  const fetchCheck = useCallback((requestedFormat) => {
    let alive = true
    setLoading(true)
    setQuestion(null)
    setAnswer('')
    setSelectedIndex(null)
    setGap(null)
    setSuggestedPrerequisite(null)
    setPrerequisiteHandled(null)
    getStepCheck(step.id, requestedFormat)
      .then((res) => {
        if (!alive) return
        setFormat(res.format)
        setQuestion(res.question)
        setOptions(res.options || null)
      })
      .catch((err) => alive && setError(err.message))
      .finally(() => alive && setLoading(false))
    return () => {
      alive = false
    }
  }, [step.id])

  useEffect(() => fetchCheck(), [fetchCheck])

  function changeFormat(next) {
    if (next === format || busy) return
    setError(null)
    fetchCheck(next)
  }

  async function submit() {
    const isChoice = format === 'multiple_choice'
    if (busy || (isChoice ? selectedIndex == null : !answer.trim())) return
    setBusy(true)
    setError(null)
    setGap(null)
    setSuggestedPrerequisite(null)
    setPrerequisiteHandled(null)
    try {
      const res = await verifyStep(step.id, isChoice ? null : answer.trim(),
        isChoice ? selectedIndex : null, confidence)
      if (res.passed) {
        onPassed()
      } else {
        setGap(res.gap || 'That answer does not hold up yet.')
        setSuggestedPrerequisite(res.suggestedPrerequisite || null)
        setPrerequisiteWhy(res.suggestedPrerequisiteWhy || null)
      }
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  async function acceptPrerequisite() {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await applyReformulate(step.id, {
        kind: 'add_prerequisite',
        prerequisite: suggestedPrerequisite,
        sourceUpdatedAt: step.updatedAt,
      })
      setPrerequisiteHandled('accepted')
      onChanged?.()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  const isChoice = format === 'multiple_choice'

  return (
    // A title so the dialog has an accessible name (V4-4.2, 2026-07-30 user audit — this and
    // IdeaDetailModal were the two Modal consumers that skipped it, leaving a screen reader
    // announcing an unnamed "dialog"). Visually near-identical to before: the verify-context
    // line right below already reads "Before this counts as done:", so this doesn't repeat
    // that — it's here for the accessible name, not as new visible copy.
    <Modal onClose={onClose} size="md" title="Check your understanding">
      <p className="verify-context">Before this counts as done:</p>

      <div className="verify-format-row">
        {FORMATS.map((f) => (
          <Chip
            key={f.value}
            toggle
            tone="brass"
            pressed={format === f.value}
            onClick={() => changeFormat(f.value)}
            disabled={busy || loading}
          >
            {f.label}
          </Chip>
        ))}
      </div>

      {loading ? (
        <p className="verify-faint">Writing a check…</p>
      ) : question ? (
        <>
          <h2 className="verify-question">{question}</h2>
          {isChoice ? (
            <div className="verify-options">
              {options.map((o, i) => (
                <button
                  key={i}
                  type="button"
                  className={'verify-option' + (selectedIndex === i ? ' is-selected' : '')}
                  onClick={() => setSelectedIndex(i)}
                  disabled={busy}
                >
                  {o}
                </button>
              ))}
            </div>
          ) : (
            <TextArea
              className="verify-answer"
              value={answer}
              onChange={(e) => setAnswer(e.target.value)}
              placeholder={
                format === 'code_challenge'
                  ? 'Write the code (or precise pseudocode)…'
                  : format === 'scenario'
                    ? 'What would you do, and why…'
                    : 'Answer in your own words…'
              }
              rows={4}
              autoFocus
            />
          )}
          <div aria-live="polite">
            {gap && <p className="verify-gap">{gap}</p>}
          </div>
          {suggestedPrerequisite && !prerequisiteHandled && (
            <div className="verify-prerequisite">
              <p className="verify-prerequisite-text">
                Something might be missing first: "{suggestedPrerequisite}"
              </p>
              {prerequisiteWhy && <p className="verify-prerequisite-why">{prerequisiteWhy}</p>}
              <span className="verify-prerequisite-actions">
                <button className="step-edit-btn" onClick={acceptPrerequisite} disabled={busy}>
                  Accept
                </button>
                <button className="step-edit-btn" onClick={() => setPrerequisiteHandled('dismissed')} disabled={busy}>
                  Dismiss
                </button>
              </span>
            </div>
          )}
          {prerequisiteHandled === 'accepted' && (
            <p className="verify-prerequisite-done">Added as a step before this one.</p>
          )}
          {error && <p className="verify-error">{error}</p>}
          <div className="verify-confidence">
            <span>How sure were you?</span>
            {[1, 2, 3].map((value) => (
              <Chip key={value} toggle pressed={confidence === value} onClick={() => setConfidence(value)}>
                {value === 1 ? 'Not sure' : value === 2 ? 'Somewhat' : 'Very sure'}
              </Chip>
            ))}
          </div>
          <div className="verify-actions">
            <Button variant="danger" className="verify-override" onClick={onOverride} disabled={busy}>
              Mark done anyway
            </Button>
            <Button
              variant="primary"
              onClick={submit}
              disabled={busy || (isChoice ? selectedIndex == null : !answer.trim())}
            >
              {busy ? 'Checking…' : gap ? 'Try again' : 'Check my answer'}
            </Button>
          </div>
        </>
      ) : (
        <p className="verify-error">{error || 'Could not write a check.'}</p>
      )}
    </Modal>
  )
}
