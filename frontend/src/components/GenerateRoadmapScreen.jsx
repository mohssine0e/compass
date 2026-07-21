import { useState } from 'react'
import { createRoadmap, generateRoadmap } from '../api'
import { Button } from './ui'
import './NewRoadmapScreen.css'
import './GenerateRoadmapScreen.css'

let cidCounter = 0
function nextCid() {
  cidCounter += 1
  return cidCounter
}

// AI drafts a roadmap's top-level shape from a goal; the user edits and owns it before it's
// kept (Phase 4, reshaped by Phase 13). Three phases: state a goal → answer 1–2 clarifying
// questions → edit the proposed MODULE OUTLINE. Individual steps aren't drafted here — each
// module is expanded into its own steps later, on demand, from the roadmap view.
export default function GenerateRoadmapScreen({ initialGoal, onCreated, onManual, onCancel }) {
  const [phase, setPhase] = useState('goal') // goal | questions | outline
  const [goal, setGoal] = useState(initialGoal || '')
  const [questions, setQuestions] = useState([])
  const [answers, setAnswers] = useState([])
  const [title, setTitle] = useState('')
  const [modules, setModules] = useState([])
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
      if (res.status === 'outline') {
        showOutline(res)
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
      showOutline(res)
    } catch (err) {
      fail(err)
    }
  }

  function showOutline(res) {
    setTitle(res.title || '')
    const raw = res.modules && res.modules.length ? res.modules : [{ title: '', scope: '' }]
    setModules(raw.map((m) => ({ cid: nextCid(), title: m.title || '', scope: m.scope || '' })))
    setSkipped(res.skipped || [])
    setSources(res.sources || [])
    setPhase('outline')
    setBusy(false)
  }

  const cleanModules = modules.filter((m) => m.title.trim())
  const canCreate = title.trim().length > 0 && cleanModules.length > 0 && !busy

  async function accept() {
    if (!canCreate) return
    setBusy(true)
    setError(null)
    try {
      const roadmap = await createRoadmap({
        title: title.trim(),
        modules: cleanModules.map((m) => ({ title: m.title.trim(), scope: m.scope.trim() || null })),
      })
      onCreated?.(roadmap.id)
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  function setModuleField(cid, field, value) {
    setModules((prev) => prev.map((m) => (m.cid === cid ? { ...m, [field]: value } : m)))
  }
  function addModule() {
    setModules((prev) => [...prev, { cid: nextCid(), title: '', scope: '' }])
  }
  function removeModule(cid) {
    setModules((prev) => (prev.length > 1 ? prev.filter((m) => m.cid !== cid) : prev))
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
            <Button variant="ghost" onClick={onCancel}>
              Cancel
            </Button>
            {unavailable ? (
              <Button variant="primary" onClick={onManual}>
                Write it yourself
              </Button>
            ) : (
              <Button variant="primary" onClick={askQuestions} disabled={!goal.trim() || busy}>
                {busy ? 'Thinking…' : 'Draft an outline'}
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
              {busy ? 'Drafting…' : 'Draft the outline'}
            </Button>
          </div>
        </>
      )}

      {phase === 'outline' && (
        <>
          <p className="gen-lead">
            A shape, not a full plan yet. Change anything, then expand each module into steps
            when you're ready to work on it.
          </p>
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
            {modules.map((m, i) => (
              <div className="gen-step" key={m.cid}>
                <div className="step-row">
                  <span className="step-index">{i + 1}</span>
                  <input
                    className="step-input"
                    value={m.title}
                    onChange={(e) => setModuleField(m.cid, 'title', e.target.value)}
                    placeholder={`Module ${i + 1}`}
                  />
                  <button
                    type="button"
                    className="step-remove"
                    onClick={() => removeModule(m.cid)}
                    aria-label={`Remove module ${i + 1}`}
                    disabled={modules.length <= 1}
                  >
                    ×
                  </button>
                </div>
                <input
                  className="step-input gen-module-scope"
                  value={m.scope}
                  onChange={(e) => setModuleField(m.cid, 'scope', e.target.value)}
                  placeholder="What falls under this module (optional)"
                />
              </div>
            ))}
            <Button type="button" variant="ghost" className="step-add" onClick={addModule}>
              + Add module
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
