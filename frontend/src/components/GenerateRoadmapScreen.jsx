import { useState } from 'react'
import { createRoadmap, generateRoadmap } from '../api'
import { Button } from './ui'
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
      resources: (s.resources || []).map((r) => ({ rcid: nextCid(), ...r })),
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
      resources: (s.resources || []).map((r) => ({
        title: r.title,
        url: r.url,
        format: r.format,
        sourceType: r.sourceType,
        estimatedTime: r.estimatedTime,
        aiGroundingSource: r.aiGroundingSource,
      })),
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

  function updateStepResources(cid, updater) {
    setSteps((prev) =>
      prev.map((s) => (s.cid === cid ? { ...s, resources: updater(s.resources || []) } : s))
    )
  }
  function removeResource(cid, rcid) {
    updateStepResources(cid, (rs) => rs.filter((r) => r.rcid !== rcid))
  }
  function moveResource(cid, rcid, dir) {
    updateStepResources(cid, (rs) => {
      const i = rs.findIndex((r) => r.rcid === rcid)
      const j = i + dir
      if (i < 0 || j < 0 || j >= rs.length) return rs
      const next = [...rs]
      ;[next[i], next[j]] = [next[j], next[i]]
      return next
    })
  }
  function addOwnResource(cid, title, url) {
    const t = title.trim()
    const u = url.trim()
    if (!t || !u) return
    updateStepResources(cid, (rs) => [
      ...rs,
      { rcid: nextCid(), title: t, url: u, format: 'written', sourceType: 'community' },
    ])
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
            <Button variant="ghost" onClick={onCancel}>
              Cancel
            </Button>
            {unavailable ? (
              <Button variant="primary" onClick={onManual}>
                Write it yourself
              </Button>
            ) : (
              <Button variant="primary" onClick={askQuestions} disabled={!goal.trim() || busy}>
                {busy ? 'Thinking…' : 'Draft steps'}
              </Button>
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
            <Button variant="ghost" onClick={onCancel}>
              Cancel
            </Button>
            <Button variant="primary" onClick={propose} disabled={busy}>
              {busy ? 'Drafting…' : 'Draft the steps'}
            </Button>
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
                <StepResources
                  step={step}
                  onRemove={(rcid) => removeResource(step.cid, rcid)}
                  onMove={(rcid, dir) => moveResource(step.cid, rcid, dir)}
                  onAdd={(title, url) => addOwnResource(step.cid, title, url)}
                />
              </div>
            ))}
            <Button type="button" variant="ghost" className="step-add" onClick={addStep}>
              + Add step
            </Button>
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
            <Button variant="ghost" onClick={onCancel}>
              Cancel
            </Button>
            <Button variant="primary" onClick={accept} disabled={!canCreate}>
              {busy ? 'Creating…' : 'Create roadmap'}
            </Button>
          </div>
        </>
      )}
    </div>
  )
}

// Curated resources for one proposed step (Phase 7.5): remove, reorder, or add your own.
function StepResources({ step, onRemove, onMove, onAdd }) {
  const [adding, setAdding] = useState(false)
  const [title, setTitle] = useState('')
  const [url, setUrl] = useState('')
  const resources = step.resources || []

  function submit() {
    onAdd(title, url)
    setTitle('')
    setUrl('')
    setAdding(false)
  }

  return (
    <div className="gen-resources">
      {resources.map((r, i) => (
        <div className="gen-resource" key={r.rcid}>
          <a className="gen-resource-title" href={r.url} target="_blank" rel="noreferrer">
            {r.title}
          </a>
          <span className="gen-resource-meta">
            {r.format && <span className="gen-badge">{r.format}</span>}
            {r.estimatedTime && <span className="gen-resource-time">{r.estimatedTime}</span>}
          </span>
          <span className="gen-resource-actions">
            <button
              type="button"
              className="step-move-btn"
              onClick={() => onMove(r.rcid, -1)}
              disabled={i === 0}
              aria-label="Move resource up"
            >
              ↑
            </button>
            <button
              type="button"
              className="step-move-btn"
              onClick={() => onMove(r.rcid, 1)}
              disabled={i === resources.length - 1}
              aria-label="Move resource down"
            >
              ↓
            </button>
            <button
              type="button"
              className="step-remove"
              onClick={() => onRemove(r.rcid)}
              aria-label="Remove resource"
            >
              ×
            </button>
          </span>
        </div>
      ))}
      {adding ? (
        <div className="gen-resource-add">
          <input
            className="step-input"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Resource title"
            autoFocus
          />
          <input
            className="step-input"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder="https://…"
          />
          <button className="step-edit-btn" onClick={submit} disabled={!title.trim() || !url.trim()}>
            Add
          </button>
          <button className="step-edit-btn" onClick={() => setAdding(false)}>
            Cancel
          </button>
        </div>
      ) : (
        <button type="button" className="gen-resource-addbtn" onClick={() => setAdding(true)}>
          + Add a resource
        </button>
      )}
    </div>
  )
}
