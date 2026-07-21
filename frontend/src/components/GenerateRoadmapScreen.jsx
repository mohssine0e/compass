import { useEffect, useState } from 'react'
import { createRoadmap, generateRoadmap } from '../api'
import { Button } from './ui'
import StepProposalEditor, { fromProposedSteps, toDraftSteps } from './StepProposalEditor'
import './NewRoadmapScreen.css'
import './GenerateRoadmapScreen.css'

let cidCounter = 0
function nextCid() {
  cidCounter += 1
  return cidCounter
}

// What each backend generation stage (Phase 18) reads as while waiting — the free-tier tertiary
// AI provider can take up to a minute, so naming the actual stage plus a live elapsed-time count
// replaces a frozen "Thinking…" button with something that's honestly still moving.
const STAGE_LABELS = {
  CLARIFYING: 'Thinking about what to ask',
  ASSESSING: 'Sizing up your goal',
  DRAFTING: 'Drafting',
  FINDING_RESOURCES: 'Finding resources',
}

function formatElapsed(seconds) {
  if (seconds < 60) return `${seconds}s`
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`
}

// AI drafts a roadmap's top-level shape from a goal; the user edits and owns it before it's
// kept (Phase 4, reshaped by Phases 13 and 17). Up to four phases: state a goal → answer 0–4
// goal-specific clarifying questions → optionally one genuine follow-up round → edit the
// proposed MODULE OUTLINE. Individual steps aren't drafted here — each module is expanded into
// its own steps later, on demand, from the roadmap view. The question count and content are
// adaptive per goal, not a fixed pair — a narrow goal with a rich profile can skip straight to
// the outline; a founder in a hurry can always skip ahead and let the system state its
// assumptions instead (see `skipAndDraft`).
export default function GenerateRoadmapScreen({ initialGoal, onCreated, onManual, onCancel }) {
  const [phase, setPhase] = useState('goal') // goal | questions | outline | flat
  const [goal, setGoal] = useState(initialGoal || '')
  const [questions, setQuestions] = useState([])
  const [answers, setAnswers] = useState([])
  // Clarifications already answered in a prior round, carried forward once a genuine follow-up
  // round is shown, so the final draft call sends every round's answers merged together.
  const [priorClarifications, setPriorClarifications] = useState([])
  const [isFollowUpRound, setIsFollowUpRound] = useState(false)
  const [title, setTitle] = useState('')
  const [interpretation, setInterpretation] = useState(null)
  const [modules, setModules] = useState([])
  // Populated instead of `modules` when the assessment (Phase 18) judges the goal small enough
  // for one flat step list rather than named modules — same shape/editor as a module's own steps.
  const [flatSteps, setFlatSteps] = useState([])
  const [skipped, setSkipped] = useState([])
  const [sources, setSources] = useState([])
  // The shared goal-scope read (Phase 18) that sized this draft — round-tripped on create so a
  // later module-expand call reads the same numbers instead of re-guessing.
  const [assessment, setAssessment] = useState(null)
  const [busy, setBusy] = useState(false)
  // Live progress while busy (Phase 18): which backend stage is running, plus a ticking
  // elapsed-time count so a slow AI call reads as "still working," not "stuck."
  const [stage, setStage] = useState(null)
  const [elapsedSeconds, setElapsedSeconds] = useState(0)
  const [error, setError] = useState(null)
  // 503 = drafting unavailable; offer the manual form instead of a dead end.
  const [unavailable, setUnavailable] = useState(false)

  useEffect(() => {
    if (!busy) {
      setElapsedSeconds(0)
      return
    }
    const start = Date.now()
    const id = setInterval(() => setElapsedSeconds(Math.floor((Date.now() - start) / 1000)), 1000)
    return () => clearInterval(id)
  }, [busy])

  function fail(err) {
    setError(err.message)
    setUnavailable(/unavailable/i.test(err.message))
    setBusy(false)
  }

  async function askQuestions() {
    if (!goal.trim() || busy) return
    setBusy(true)
    setError(null)
    setStage(null)
    try {
      const res = await generateRoadmap(
        { goal: goal.trim(), clarifications: null, skipFollowUp: false },
        setStage
      )
      if (res.status === 'outline' || res.status === 'proposal') {
        showResult(res)
      } else {
        setPriorClarifications([])
        setIsFollowUpRound(false)
        setQuestions(res.questions || [])
        setAnswers((res.questions || []).map(() => ''))
        setPhase('questions')
        setBusy(false)
      }
    } catch (err) {
      fail(err)
    }
  }

  // Skip clarification entirely — draft straight from the goal, with the model stating its
  // assumptions plainly in the outline instead of asking anything first.
  async function skipAndDraft() {
    if (!goal.trim() || busy) return
    setBusy(true)
    setError(null)
    setStage(null)
    try {
      const res = await generateRoadmap(
        { goal: goal.trim(), clarifications: [], skipFollowUp: true },
        setStage
      )
      showResult(res)
    } catch (err) {
      fail(err)
    }
  }

  async function propose() {
    if (busy) return
    setBusy(true)
    setError(null)
    setStage(null)
    try {
      const roundAnswers = questions.map((q, i) => ({ question: q, answer: answers[i] || '' }))
      const clarifications = [...priorClarifications, ...roundAnswers]
      const res = await generateRoadmap(
        { goal: goal.trim(), clarifications, skipFollowUp: isFollowUpRound },
        setStage
      )
      if (res.status === 'needs_clarification' && !isFollowUpRound) {
        // A genuine follow-up round, conditioned on what was just answered — show it, then cap
        // at one more round (the next submit sends skipFollowUp: true regardless of the answer).
        setPriorClarifications(clarifications)
        setIsFollowUpRound(true)
        setQuestions(res.questions || [])
        setAnswers((res.questions || []).map(() => ''))
        setBusy(false)
      } else {
        showResult(res)
      }
    } catch (err) {
      fail(err)
    }
  }

  // The assessment (Phase 18) gates the shape: "proposal" means the goal was small enough for
  // one flat step list (reusing the same editor a module's own expand uses); "outline" means it
  // genuinely breaks into modules, drafted and expanded one at a time as before.
  function showResult(res) {
    setTitle(res.title || '')
    setInterpretation(res.interpretation || null)
    setSkipped(res.skipped || [])
    setSources(res.sources || [])
    setAssessment(res.assessment || null)
    if (res.status === 'proposal') {
      setFlatSteps(fromProposedSteps(res.steps))
      setPhase('flat')
    } else {
      const raw = res.modules && res.modules.length ? res.modules : [{ title: '', scope: '' }]
      setModules(raw.map((m) => ({ cid: nextCid(), title: m.title || '', scope: m.scope || '' })))
      setPhase('outline')
    }
    setBusy(false)
  }

  const cleanModules = modules.filter((m) => m.title.trim())
  const cleanFlatSteps = flatSteps.filter((s) => s.text.trim())
  const canCreate = title.trim().length > 0 && !busy
    && (phase === 'flat' ? cleanFlatSteps.length > 0 : cleanModules.length > 0)

  async function accept() {
    if (!canCreate) return
    setBusy(true)
    setError(null)
    try {
      const roadmap = await createRoadmap(
        phase === 'flat'
          ? { title: title.trim(), draftSteps: toDraftSteps(flatSteps), assessment }
          : {
              title: title.trim(),
              modules: cleanModules.map((m) => ({ title: m.title.trim(), scope: m.scope.trim() || null })),
              assessment,
            }
      )
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
          {busy && (
            <p className="gen-progress">
              {STAGE_LABELS[stage] || 'Working'}… ({formatElapsed(elapsedSeconds)})
            </p>
          )}
          <div className="roadmap-actions">
            {error && <span className="roadmap-error">{error}</span>}
            <Button variant="ghost" onClick={onCancel}>
              Cancel
            </Button>
            {!unavailable && (
              <Button variant="ghost" onClick={skipAndDraft} disabled={!goal.trim() || busy}>
                Skip — just draft it
              </Button>
            )}
            {unavailable ? (
              <Button variant="primary" onClick={onManual}>
                Write it yourself
              </Button>
            ) : (
              <Button variant="primary" onClick={askQuestions} disabled={!goal.trim() || busy}>
                {busy ? 'Working…' : 'Draft an outline'}
              </Button>
            )}
          </div>
        </>
      )}

      {phase === 'questions' && (
        <>
          <p className="gen-lead">
            {isFollowUpRound ? 'One more thing.' : 'A couple of things first, so the plan fits you.'}
          </p>
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
          {busy && (
            <p className="gen-progress">
              {STAGE_LABELS[stage] || 'Working'}… ({formatElapsed(elapsedSeconds)})
            </p>
          )}
          <div className="roadmap-actions">
            {error && <span className="roadmap-error">{error}</span>}
            <Button variant="ghost" onClick={onCancel}>
              Cancel
            </Button>
            <Button variant="primary" onClick={propose} disabled={busy}>
              {busy ? 'Working…' : 'Draft the outline'}
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
          {interpretation && <p className="gen-interpretation">{interpretation}</p>}
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

      {phase === 'flat' && (
        <>
          <p className="gen-lead">
            A step list, not a full plan yet. Change anything before keeping it.
          </p>
          {interpretation && <p className="gen-interpretation">{interpretation}</p>}
          <input
            className="roadmap-title"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Roadmap title"
          />
          <StepProposalEditor
            steps={flatSteps}
            onChange={setFlatSteps}
            skipped={skipped}
            sources={sources}
          />
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
