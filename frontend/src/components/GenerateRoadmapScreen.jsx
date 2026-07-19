import { useState } from 'react'
import { createRoadmap, generateRoadmap } from '../api'
import './NewRoadmapScreen.css'
import './GenerateRoadmapScreen.css'

// AI drafts a roadmap from a goal; the user edits and owns it before it's kept (Phase 4).
// Three phases: state a goal → answer 1–2 clarifying questions → edit the proposed steps.
export default function GenerateRoadmapScreen({ onCreated, onManual, onCancel }) {
  const [phase, setPhase] = useState('goal') // goal | questions | proposal
  const [goal, setGoal] = useState('')
  const [questions, setQuestions] = useState([])
  const [answers, setAnswers] = useState([])
  const [title, setTitle] = useState('')
  const [steps, setSteps] = useState([])
  const [skipped, setSkipped] = useState([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  // 503 = drafting unavailable; offer the manual form instead of a dead end.
  const [unavailable, setUnavailable] = useState(false)

  function fail(err) {
    setError(err.message)
    setUnavailable(/unavailable/i.test(err.message))
    setBusy(false)
  }

  async function askQuestions() {
    if (!goal.trim() || busy) return
    setBusy(true)
    setError(null)
    try {
      const res = await generateRoadmap({ goal: goal.trim() })
      if (res.status === 'proposal') {
        showProposal(res)
      } else {
        setQuestions(res.questions || [])
        setAnswers((res.questions || []).map(() => ''))
        setPhase('questions')
        setBusy(false)
      }
    } catch (err) {
      fail(err)
    }
  }

  async function propose() {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      const clarifications = questions.map((q, i) => ({ question: q, answer: answers[i] || '' }))
      const res = await generateRoadmap({ goal: goal.trim(), clarifications })
      showProposal(res)
    } catch (err) {
      fail(err)
    }
  }

  function showProposal(res) {
    setTitle(res.title || '')
    setSteps(res.steps && res.steps.length ? res.steps : [''])
    setSkipped(res.skipped || [])
    setPhase('proposal')
    setBusy(false)
  }

  const cleanSteps = steps.map((s) => s.trim()).filter(Boolean)
  const canCreate = title.trim().length > 0 && cleanSteps.length > 0 && !busy

  async function accept() {
    if (!canCreate) return
    setBusy(true)
    setError(null)
    try {
      const roadmap = await createRoadmap({ title: title.trim(), steps: cleanSteps })
      onCreated?.(roadmap.id)
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  function setStep(index, value) {
    setSteps((prev) => prev.map((s, i) => (i === index ? value : s)))
  }
  function addStep() {
    setSteps((prev) => [...prev, ''])
  }
  function removeStep(index) {
    setSteps((prev) => (prev.length > 1 ? prev.filter((_, i) => i !== index) : prev))
  }

  return (
    <div className="roadmap-form">
      {phase === 'goal' && (
        <>
          <label className="gen-label">What do you want a roadmap for?</label>
          <textarea
            className="roadmap-notes gen-goal"
            value={goal}
            onChange={(e) => {
              setGoal(e.target.value)
              if (error) setError(null)
            }}
            placeholder="e.g. learn to read Arabic, ship a side project, get comfortable with algorithms"
            rows={3}
            autoFocus
          />
          <div className="roadmap-actions">
            {error && <span className="roadmap-error">{error}</span>}
            <button className="btn-ghost" onClick={onCancel}>
              Cancel
            </button>
            {unavailable ? (
              <button className="btn-primary" onClick={onManual}>
                Write it yourself
              </button>
            ) : (
              <button className="btn-primary" onClick={askQuestions} disabled={!goal.trim() || busy}>
                {busy ? 'Thinking…' : 'Draft steps'}
              </button>
            )}
          </div>
        </>
      )}

      {phase === 'questions' && (
        <>
          <p className="gen-lead">A couple of things first, so the plan fits you.</p>
          <div className="gen-questions">
            {questions.map((q, i) => (
              <div className="gen-question" key={i}>
                <label className="gen-question-text">{q}</label>
                <input
                  className="step-input"
                  value={answers[i]}
                  onChange={(e) =>
                    setAnswers((prev) => prev.map((a, j) => (j === i ? e.target.value : a)))
                  }
                  autoFocus={i === 0}
                />
              </div>
            ))}
          </div>
          <div className="roadmap-actions">
            {error && <span className="roadmap-error">{error}</span>}
            <button className="btn-ghost" onClick={onCancel}>
              Cancel
            </button>
            <button className="btn-primary" onClick={propose} disabled={busy}>
              {busy ? 'Drafting…' : 'Draft the steps'}
            </button>
          </div>
        </>
      )}

      {phase === 'proposal' && (
        <>
          <p className="gen-lead">A draft. Change anything before you keep it — it's yours.</p>
          {skipped.length > 0 && (
            <div className="gen-skipped">
              <span className="gen-skipped-label">Skipped, based on your profile:</span>
              <ul className="gen-skipped-list">
                {skipped.map((s, i) => (
                  <li key={i}>{s}</li>
                ))}
              </ul>
            </div>
          )}
          <input
            className="roadmap-title"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Roadmap title"
          />
          <div className="roadmap-steps">
            {steps.map((step, i) => (
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
                  disabled={steps.length <= 1}
                >
                  ×
                </button>
              </div>
            ))}
            <button type="button" className="btn-ghost step-add" onClick={addStep}>
              + Add step
            </button>
          </div>
          <div className="roadmap-actions">
            {error && <span className="roadmap-error">{error}</span>}
            <button className="btn-ghost" onClick={onCancel}>
              Cancel
            </button>
            <button className="btn-primary" onClick={accept} disabled={!canCreate}>
              {busy ? 'Creating…' : 'Create roadmap'}
            </button>
          </div>
        </>
      )}
    </div>
  )
}
