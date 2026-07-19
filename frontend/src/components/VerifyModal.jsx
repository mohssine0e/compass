import { useEffect, useState } from 'react'
import { getStepCheck, verifyStep } from '../api'
import './VerifyModal.css'

// The check gate before a step counts as done (Phase 8). Fetches a fair question, takes the
// user's answer, and judges it — on a pass the step is marked done; on a miss it names the
// specific gap in the self-talk voice and stays open. "Mark done anyway" is an honest escape
// hatch (self-reported), so the gate never traps you.
export default function VerifyModal({ step, onClose, onPassed, onOverride }) {
  const [question, setQuestion] = useState(null)
  const [loading, setLoading] = useState(true)
  const [answer, setAnswer] = useState('')
  const [gap, setGap] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    let alive = true
    setLoading(true)
    getStepCheck(step.id)
      .then((res) => alive && setQuestion(res.question))
      .catch((err) => alive && setError(err.message))
      .finally(() => alive && setLoading(false))
    return () => {
      alive = false
    }
  }, [step.id])

  async function submit() {
    if (busy || !answer.trim()) return
    setBusy(true)
    setError(null)
    setGap(null)
    try {
      const res = await verifyStep(step.id, answer.trim())
      if (res.passed) {
        onPassed()
      } else {
        setGap(res.gap || 'That answer does not hold up yet.')
      }
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="verify-overlay" onClick={onClose}>
      <div className="verify-panel" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <button className="deep-close" onClick={onClose} aria-label="Close">
          ×
        </button>
        <p className="verify-context">Before this counts as done:</p>

        {loading ? (
          <p className="verify-faint">Writing a check…</p>
        ) : question ? (
          <>
            <h2 className="verify-question">{question}</h2>
            <textarea
              className="verify-answer"
              value={answer}
              onChange={(e) => setAnswer(e.target.value)}
              placeholder="Answer in your own words…"
              rows={4}
              autoFocus
            />
            {gap && (
              <p className="verify-gap">{gap}</p>
            )}
            {error && <p className="verify-error">{error}</p>}
            <div className="verify-actions">
              <button className="step-undo-btn verify-override" onClick={onOverride} disabled={busy}>
                Mark done anyway
              </button>
              <button className="btn-primary" onClick={submit} disabled={busy || !answer.trim()}>
                {busy ? 'Checking…' : gap ? 'Try again' : 'Check my answer'}
              </button>
            </div>
          </>
        ) : (
          <p className="verify-error">{error || 'Could not write a check.'}</p>
        )}
      </div>
    </div>
  )
}
