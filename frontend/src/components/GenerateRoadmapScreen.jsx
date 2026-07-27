import { useEffect, useState } from 'react'
import { createRoadmap, generateRoadmap, insertModule, proposeSubtopicModule, suggestResources } from '../api'
import { Button, Card } from './ui'
import StepProposalEditor, { attachIssueCids, fromProposedSteps, toDraftSteps } from './StepProposalEditor'
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

// RB-3.6: a match-type-specific lead line for the Canonical Topic Match decision prompt.
function topicMatchLead(match) {
  const name = match.candidateName
  if (match.matchType === 'exact') return `You already have a roadmap for this — "${name}".`
  if (match.matchType === 'subtopic') return `This looks like part of "${name}", which you already have.`
  if (match.matchType === 'prerequisite') {
    return `This looks like it should come before "${name}", which you already have.`
  }
  return `This looks related to "${name}", which you already have.`
}

// AI drafts a roadmap's top-level shape from a goal; the user edits and owns it before it's
// kept (Phase 4, reshaped by Phases 13 and 17). Up to four phases: state a goal → answer 0–4
// goal-specific clarifying questions → optionally one genuine follow-up round → edit the
// proposed MODULE OUTLINE. Individual steps aren't drafted here — each module is expanded into
// its own steps later, on demand, from the roadmap view. The question count and content are
// adaptive per goal, not a fixed pair — a narrow goal with a rich profile can skip straight to
// the outline; a founder in a hurry can always skip ahead and let the system state its
// assumptions instead (see `skipAndDraft`).
export default function GenerateRoadmapScreen({ initialGoal, initialResult, onCreated, onManual, onCancel }) {
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
  // True while resources for the flat proposal are being found as a separate follow-up call
  // (see suggestResources in api.js) — the step structure is shown immediately, resources fill
  // in moments later rather than one combined wait.
  const [resourcesPending, setResourcesPending] = useState(false)
  const [issues, setIssues] = useState([])
  const [skipped, setSkipped] = useState([])
  const [sources, setSources] = useState([])
  // The shared goal-scope read (Phase 18) that sized this draft — round-tripped on create so a
  // later module-expand call reads the same numbers instead of re-guessing.
  const [assessment, setAssessment] = useState(null)
  // TASK/MINI/TOPIC/CAREER classification (RB-2) — computed by the backend on the first turn,
  // echoed back on later turns of the same goal so it isn't re-classified, and round-tripped on
  // create so it lands on the roadmap entry. Not shown anywhere yet.
  const [tier, setTier] = useState(null)
  // Set only when the goal classified as TASK — the backend already created the task entry and
  // this holds its acknowledgment line; no roadmap was drafted (RB-2.2).
  const [routedTask, setRoutedTask] = useState(null)
  // Set only when a Canonical Topic Match found something worth a decision (RB-3) — nothing
  // generated yet. { matchType, confidence, reasoning, candidateTopicId, candidateName,
  // candidateRoadmapId, candidateSubtopics }.
  const [topicMatch, setTopicMatch] = useState(null)
  // The AI-drafted module proposal for a confirmed SUBTOPIC match (RB-3.8), pending accept.
  const [subtopicProposal, setSubtopicProposal] = useState(null)
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

  // RB-6.4: the unified intake card already ran classify + clarify and resolved an outline for
  // a TOPIC/CAREER goal — hand off here instead of re-drafting from scratch. showResult is a
  // function declaration (hoisted), so referencing it before its later definition is safe.
  useEffect(() => {
    if (initialResult) {
      setTier(initialResult.tier || null)
      showResult(initialResult)
    }
    // Only ever meant to run once, on mount, from whatever the intake card already resolved.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

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
        { goal: goal.trim(), clarifications: null, skipFollowUp: false, tier: null },
        setStage
      )
      setTier(res.tier || null)
      if (res.status === 'routed_to_task') {
        setRoutedTask(res.routedTask)
        setPhase('task')
        setBusy(false)
      } else if (res.status === 'topic_match') {
        setTopicMatch(res.topicMatch)
        setPhase('topicMatch')
        setBusy(false)
      } else if (res.status === 'outline' || res.status === 'proposal') {
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
        { goal: goal.trim(), clarifications: [], skipFollowUp: true, tier: null },
        setStage
      )
      showResult(res)
    } catch (err) {
      fail(err)
    }
  }

  // RB-3: the founder confirmed this is genuinely a new topic despite the surface similarity —
  // resubmit with skipTopicMatch so the same match prompt doesn't just show again.
  async function startSeparateGoal() {
    if (busy) return
    setBusy(true)
    setError(null)
    setStage(null)
    setTopicMatch(null)
    try {
      const res = await generateRoadmap(
        { goal: goal.trim(), clarifications: null, skipFollowUp: false, tier, skipTopicMatch: true },
        setStage
      )
      setTier(res.tier || tier)
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

  // RB-3.7: an EXACT (or PREREQUISITE, same treatment) match — open the existing roadmap
  // directly, no generation at all.
  function openMatchedRoadmap() {
    if (topicMatch?.candidateRoadmapId) {
      onCreated?.(topicMatch.candidateRoadmapId)
    }
  }

  // RB-3.8: SUBTOPIC confirmed — draft one module scoped to this goal, to review before adding.
  async function proposeSubtopic() {
    if (!topicMatch?.candidateRoadmapId || busy) return
    setBusy(true)
    setError(null)
    try {
      const proposal = await proposeSubtopicModule(topicMatch.candidateRoadmapId, goal.trim())
      setSubtopicProposal(proposal)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  async function acceptSubtopic() {
    if (!subtopicProposal || !topicMatch?.candidateRoadmapId || busy) return
    setBusy(true)
    setError(null)
    try {
      await insertModule(topicMatch.candidateRoadmapId, subtopicProposal.title, subtopicProposal.scope, null)
      onCreated?.(topicMatch.candidateRoadmapId)
    } catch (err) {
      setError(err.message)
      setBusy(false)
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
        { goal: goal.trim(), clarifications, skipFollowUp: isFollowUpRound, tier },
        setStage
      )
      setTier(res.tier || tier)
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
      const editorSteps = fromProposedSteps(res.steps)
      setFlatSteps(editorSteps)
      setIssues(attachIssueCids(editorSteps, res.issues))
      setPhase('flat')
      fetchResourcesFor(editorSteps)
    } else {
      const raw = res.modules && res.modules.length ? res.modules : [{ title: '', scope: '' }]
      setModules(raw.map((m) => ({ cid: nextCid(), title: m.title || '', scope: m.scope || '' })))
      setPhase('outline')
    }
    setBusy(false)
  }

  // The flat proposal's steps are already shown; find their resources as a quick follow-up
  // (POST /resources/suggest) rather than making the founder wait for both before seeing the
  // plan at all. Matches by cid, not array position, so it's still correct even if the founder
  // edits/removes a step while resources are still being found.
  async function fetchResourcesFor(stepsSnapshot) {
    const cids = stepsSnapshot.map((s) => s.cid)
    const stepTexts = stepsSnapshot.map((s) => s.text)
    setResourcesPending(true)
    try {
      const resourceLists = await suggestResources({ scope: goal, stepTexts, roadmapId: null })
      setFlatSteps((prev) =>
        prev.map((s) => {
          const idx = cids.indexOf(s.cid)
          const found = idx >= 0 ? resourceLists[idx] : null
          return found && found.length
            ? { ...s, resources: found.map((r) => ({ rcid: nextCid(), ...r })) }
            : s
        })
      )
    } catch {
      // Best-effort — a failed resources fetch just leaves steps without suggestions; the
      // founder can always add their own via StepProposalEditor.
    } finally {
      setResourcesPending(false)
    }
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
          ? { title: title.trim(), draftSteps: toDraftSteps(flatSteps), assessment, tier }
          : {
              title: title.trim(),
              modules: cleanModules.map((m) => ({ title: m.title.trim(), scope: m.scope.trim() || null })),
              assessment,
              tier,
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
          <Card className="gen-goal-card">
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
                {/* Only the stage name is announced (V3-7.3) — the elapsed count ticks every
                    second and would otherwise get read aloud on every tick. */}
                <span aria-live="polite">{STAGE_LABELS[stage] || 'Working'}…</span>{' '}
                <span aria-hidden="true">({formatElapsed(elapsedSeconds)})</span>
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
          </Card>
        </>
      )}

      {phase === 'task' && routedTask && (
        <>
          <p className="gen-lead">
            This read as a task, not a roadmap — no plan needed for it.
          </p>
          <p className="gen-task-ack">{routedTask.acknowledgment || routedTask.text}</p>
          <div className="roadmap-actions">
            <Button variant="primary" onClick={onCancel}>
              Back to roadmaps
            </Button>
          </div>
        </>
      )}

      {phase === 'topicMatch' && topicMatch && (
        <>
          <p className="gen-lead">{topicMatchLead(topicMatch)}</p>
          <p className="gen-interpretation">{topicMatch.reasoning}</p>

          {subtopicProposal ? (
            <>
              <p className="gen-lead">Add this module to “{topicMatch.candidateName}”?</p>
              <Card className="gen-step">
                <strong>{subtopicProposal.title}</strong>
                {subtopicProposal.scope && <span>{subtopicProposal.scope}</span>}
                {subtopicProposal.possibleDuplicate && (
                  <span className="roadmap-error">
                    This looks similar to a module already there — check before adding.
                  </span>
                )}
              </Card>
              <div className="roadmap-actions">
                {error && <span className="roadmap-error">{error}</span>}
                <Button variant="ghost" onClick={() => setSubtopicProposal(null)} disabled={busy}>
                  Back
                </Button>
                <Button variant="primary" onClick={acceptSubtopic} disabled={busy}>
                  {busy ? 'Adding…' : 'Add module'}
                </Button>
              </div>
            </>
          ) : (
            <div className="roadmap-actions">
              {error && <span className="roadmap-error">{error}</span>}
              <Button variant="ghost" onClick={startSeparateGoal} disabled={busy}>
                {busy ? 'Working…' : 'Start a separate one'}
              </Button>
              {topicMatch.matchType === 'subtopic' && topicMatch.candidateRoadmapId && (
                <Button variant="primary" onClick={proposeSubtopic} disabled={busy}>
                  {busy ? 'Drafting…' : 'Add as a module'}
                </Button>
              )}
              {topicMatch.matchType !== 'subtopic' && topicMatch.candidateRoadmapId && (
                <Button variant="primary" onClick={openMatchedRoadmap} disabled={busy}>
                  Open existing roadmap
                </Button>
              )}
            </div>
          )}
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
              {/* Only the stage name is announced (V3-7.3) — the elapsed count ticks every
                  second and would otherwise get read aloud on every tick. */}
              <span aria-live="polite">{STAGE_LABELS[stage] || 'Working'}…</span>{' '}
              <span aria-hidden="true">({formatElapsed(elapsedSeconds)})</span>
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
              <Card className="gen-step gen-module-card" key={m.cid}>
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
              </Card>
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
            issues={issues}
            resourcesPending={resourcesPending}
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
