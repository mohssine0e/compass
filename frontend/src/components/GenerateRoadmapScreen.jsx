import { useState } from 'react'
import { createRoadmap, generateRoadmap } from '../api'
import './NewRoadmapScreen.css'
import './GenerateRoadmapScreen.css'

// Stable client ids for proposed steps, so dependency links survive edits/removals.
let cidCounter = 0
function nextCid() {
  cidCounter += 1
  return cidCounter
}

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
  const [sources, setSources] = useState([])
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
    const raw = res.steps && res.steps.length ? res.steps : [{ text: '' }]
    // Give each step a stable client id and resolve dependsOn (an index) to the prerequisite's
    // id, so edits/removals don't scramble the links.
    const withIds = raw.map((s, i) => ({ cid: nextCid(), ...s, _index: i }))
    const steps = withIds.map((s) => ({
      cid: s.cid,
      text: s.text || '',
      kind: s.kind || 'concept',
      weight: s.weight || 'medium',
      rationale: s.rationale || null,
      dependsOnCid:
        s.dependsOn != null && withIds[s.dependsOn] ? withIds[s.dependsOn].cid : null,
    }))
    setSteps(steps)
    setSkipped(res.skipped || [])
    setSources(res.sources || [])
    setPhase('proposal')
    setBusy(false)
  }

  const cleanSteps = steps.filter((s) => s.text.trim())
  const canCreate = title.trim().length > 0 && cleanSteps.length > 0 && !busy

  async function accept() {
    if (!canCreate) return
    setBusy(true)
    setError(null)
    // Keep only steps with text, then express each prerequisite as an index into that final list.
    const kept = cleanSteps
    const indexOfCid = new Map(kept.map((s, i) => [s.cid, i]))
    const draftSteps = kept.map((s) => ({
      text: s.text.trim(),
      kind: s.kind,
      weight: s.weight,
      dependsOn: s.dependsOnCid != null && indexOfCid.has(s.dependsOnCid)
        ? indexOfCid.get(s.dependsOnCid)
        : null,
    }))
    try {
      const roadmap = await createRoadmap({ title: title.trim(), draftSteps })
      onCreated?.(roadmap.id)
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  function setStepText(cid, value) {
    setSteps((prev) => prev.map((s) => (s.cid === cid ? { ...s, text: value } : s)))
  }
  function addStep() {
    setSteps((prev) => [
      ...prev,
      { cid: nextCid(), text: '', kind: 'concept', weight: 'medium', rationale: null, dependsOnCid: null },
    ])
  }
  function removeStep(cid) {
    setSteps((prev) =>
      prev.length > 1
        ? prev
            .filter((s) => s.cid !== cid)
            // Any step that depended on the removed one loses its prerequisite link.
            .map((s) => (s.dependsOnCid === cid ? { ...s, dependsOnCid: null } : s))
        : prev
    )
  }

  const textByCid = new Map(steps.map((s) => [s.cid, s.text]))

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
              <div className="gen-step" key={step.cid}>
                <div className="step-row">
                  <span className="step-index">{i + 1}</span>
                  <input
                    className="step-input"
                    value={step.text}
                    onChange={(e) => setStepText(step.cid, e.target.value)}
                    placeholder={`Step ${i + 1}`}
                  />
                  <button
                    type="button"
                    className="step-remove"
                    onClick={() => removeStep(step.cid)}
                    aria-label={`Remove step ${i + 1}`}
                    disabled={steps.length <= 1}
                  >
                    ×
                  </button>
                </div>
                <div className="gen-step-meta">
                  {step.kind && <span className={`gen-badge kind-${step.kind}`}>{step.kind}</span>}
                  {step.weight && <span className="gen-badge weight">{step.weight}</span>}
                  {step.dependsOnCid != null && textByCid.get(step.dependsOnCid) && (
                    <span className="gen-depends">needs: {textByCid.get(step.dependsOnCid)}</span>
                  )}
                </div>
                {step.rationale && <p className="gen-rationale">{step.rationale}</p>}
              </div>
            ))}
            <button type="button" className="btn-ghost step-add" onClick={addStep}>
              + Add step
            </button>
          </div>
          {sources.length > 0 && (
            <div className="gen-sources">
              <span className="gen-sources-label">Grounded in:</span>
              <ul className="gen-sources-list">
                {sources.map((s, i) => (
                  <li key={i}>{s}</li>
                ))}
              </ul>
            </div>
          )}
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
