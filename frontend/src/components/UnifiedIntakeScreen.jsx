import { useEffect, useRef, useState } from 'react'
import {
  classifyIntent,
  createEntry,
  createRoadmap,
  explainText,
  generateRoadmap,
  insertModule,
  listCompletedSteps,
  proposeSubtopicModule,
} from '../api'
import { useSpeechRecognition } from '../hooks/useSpeechRecognition'
import StepProposalEditor, { fromProposedSteps, toDraftSteps } from './StepProposalEditor'
import VerifyModal from './VerifyModal'
import { Button } from './ui'
import './CaptureScreen.css'
import './intake.css'

// The significance caption shows only for the first few visits (Phase 22) — enough to learn
// what big/small means, then it gets out of the way. Tooltips stay for later.
const SIG_HINT_KEY = 'compass.sigHintSeen'
const SIG_HINT_MAX_VIEWS = 5

function shouldShowSigHint() {
  try {
    const seen = Number(localStorage.getItem(SIG_HINT_KEY) || 0)
    if (seen >= SIG_HINT_MAX_VIEWS) return false
    localStorage.setItem(SIG_HINT_KEY, String(seen + 1))
    return true
  } catch {
    return false
  }
}

// RB-6.2: the ambient, tentative label shown while typing — plain words, not the raw enum name.
const INTENT_LABELS = {
  IDEA: 'idea',
  DO: 'task',
  LEARN: 'topic to learn',
  PLAN_A_JOURNEY: 'roadmap',
  PRACTICE: 'practice',
  REVIEW: 'review',
  PREPARE: 'prep plan',
  EXPLORE: 'exploring',
  TROUBLESHOOT: 'troubleshooting',
  ASSESS: 'self-check',
}

// Same stage labels GenerateRoadmapScreen uses for its own job polling (Phase 18) — the
// unified intake card runs the identical backend call, so it reads the same live progress.
const GEN_STAGE_LABELS = {
  CLARIFYING: 'Thinking about what to ask',
  ASSESSING: 'Sizing up your goal',
  DRAFTING: 'Drafting',
  FINDING_RESOURCES: 'Finding resources',
}

const TRAIL_PREFIX = { idea: 'Idea', task: 'Task', troubleshoot: 'Answered', explore: 'Noted', practice: 'Reviewed' }

let cidCounter = 0
function nextCid() {
  cidCounter += 1
  return cidCounter
}

// One consistent fade/slide plays every time `stage` changes (RB-6.3) — a single shared
// transform, not a different transition hand-tuned per card type.
const MORPH_CLASS = 'intake-morph'

function topicMatchLead(match) {
  const name = match.candidateName
  if (match.matchType === 'exact') return `You already have a roadmap for this — "${name}".`
  if (match.matchType === 'subtopic') return `This looks like part of "${name}", which you already have.`
  if (match.matchType === 'prerequisite') {
    return `This looks like it should come before "${name}", which you already have.`
  }
  return `This looks related to "${name}", which you already have.`
}

// RB-6/RB-7: the unified input IS the result — submitting morphs the card in place instead of
// navigating to a disconnected screen for every case. Only two things ever navigate away: a
// created MINI roadmap (needs its own persistent home) and a TOPIC/CAREER goal handed off to
// the full roadmap builder once it needs real room (module editing) the compact card can't give
// it. Everything else — idea, task, troubleshoot, practice/review, explore/assess — resolves
// fully on this one surface, then settles into a light trail (RB-7).
export default function UnifiedIntakeScreen({ onOpenRoadmap, onOpenRoadmapBuilder, onOpenAll }) {
  const [text, setText] = useState('')
  const [significance, setSignificance] = useState(null) // 'big' | 'small' | null
  const [showSigHint] = useState(shouldShowSigHint)
  const textareaRef = useRef(null)

  // RB-6.2: ambient classification while typing — debounced, and a request-id guard so a
  // response for text that's since been edited is discarded rather than shown stale.
  const [ambient, setAmbient] = useState(null)
  const ambientReqId = useRef(0)

  // The card's current shape (RB-6.3/6.4).
  const [stage, setStage] = useState('idle')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [genStage, setGenStage] = useState(null)

  // Roadmap-drafting sub-state — the same fields GenerateRoadmapScreen tracks, condensed onto
  // this surface for the LEARN/PLAN_A_JOURNEY/PREPARE path up to the point a MINI goal resolves
  // or a TOPIC/CAREER goal is hand off.
  const [goal, setGoal] = useState('')
  const [tier, setTier] = useState(null)
  const [questions, setQuestions] = useState([])
  const [answers, setAnswers] = useState([])
  const [priorClarifications, setPriorClarifications] = useState([])
  const [isFollowUpRound, setIsFollowUpRound] = useState(false)
  const [title, setTitle] = useState('')
  const [flatSteps, setFlatSteps] = useState([])
  const [assessment, setAssessment] = useState(null)
  const [handoffResult, setHandoffResult] = useState(null)
  const [topicMatch, setTopicMatch] = useState(null)
  const [subtopicProposal, setSubtopicProposal] = useState(null)

  const [troubleshootAnswer, setTroubleshootAnswer] = useState(null)

  const [practiceSteps, setPracticeSteps] = useState([])
  const [verifyingStep, setVerifyingStep] = useState(null)

  const [doneMessage, setDoneMessage] = useState(null) // { kind, message }

  // RB-7: the last few resolved cards, most recent first — a light session trail, not a new
  // permanent data view (that's still Everything/Roadmaps/Tasks, unchanged).
  const [trail, setTrail] = useState([])

  const appendSpokenText = (chunk) => {
    const clean = chunk.trim()
    if (!clean) return
    setText((prev) => (prev ? prev.trimEnd() + ' ' : '') + clean)
    setError(null)
  }
  const speech = useSpeechRecognition({ onFinalText: appendSpokenText })

  // RB-6.2: fires ~700ms after typing stops; a stale in-flight response (text changed again
  // before it returned) is discarded via the request-id check, never shown.
  useEffect(() => {
    if (stage !== 'idle' || !text.trim()) {
      setAmbient(null)
      return undefined
    }
    const id = ++ambientReqId.current
    const t = setTimeout(async () => {
      try {
        const res = await classifyIntent(text.trim())
        if (ambientReqId.current === id) setAmbient(res)
      } catch {
        if (ambientReqId.current === id) setAmbient(null)
      }
    }, 700)
    return () => clearTimeout(t)
  }, [text, stage])

  // A resolved idea/task/troubleshoot/explore card settles into the trail on its own after a
  // moment — the founder doesn't have to dismiss it to keep going.
  useEffect(() => {
    if (stage !== 'done' || !doneMessage) return undefined
    const t = setTimeout(() => {
      pushTrail({ kind: doneMessage.kind, label: doneMessage.message })
      setStage('idle')
      setDoneMessage(null)
      textareaRef.current?.focus()
    }, 1600)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stage, doneMessage])

  function pushTrail(item) {
    setTrail((prev) => [{ cid: nextCid(), ...item }, ...prev].slice(0, 5))
  }

  function resolveDone(kind, message) {
    setStage('done')
    setDoneMessage({ kind, message })
  }

  function cancelCard() {
    setStage('idle')
    setError(null)
    setGenStage(null)
    setGoal('')
    setTier(null)
    setQuestions([])
    setAnswers([])
    setPriorClarifications([])
    setIsFollowUpRound(false)
    setTitle('')
    setFlatSteps([])
    setAssessment(null)
    setHandoffResult(null)
    setTopicMatch(null)
    setSubtopicProposal(null)
    setTroubleshootAnswer(null)
    setPracticeSteps([])
    textareaRef.current?.focus()
  }

  function handleDraftResult(res, clarificationsSoFar, wasFollowUp) {
    setTier(res.tier || tier)
    if (res.status === 'routed_to_task') {
      resolveDone('task', res.routedTask.acknowledgment || res.routedTask.text)
    } else if (res.status === 'topic_match') {
      setTopicMatch(res.topicMatch)
      setStage('topicMatch')
    } else if (res.status === 'needs_clarification' && !wasFollowUp) {
      setPriorClarifications(clarificationsSoFar)
      setIsFollowUpRound(true)
      setQuestions(res.questions || [])
      setAnswers((res.questions || []).map(() => ''))
      setStage('clarify')
    } else if (res.status === 'proposal') {
      // MINI: resolves fully on this surface — the flat step list, right here (RB-6.4).
      setTitle(res.title || '')
      setAssessment(res.assessment || null)
      setFlatSteps(fromProposedSteps(res.steps))
      setStage('flat')
    } else {
      // 'outline': TOPIC/CAREER — this needs real room; hand off once the founder's ready.
      setTitle(res.title || '')
      setHandoffResult(res)
      setStage('handoff')
    }
  }

  async function startDraft(goalText) {
    setGoal(goalText)
    const res = await generateRoadmap({ goal: goalText, clarifications: null, skipFollowUp: false, tier: null }, setGenStage)
    handleDraftResult(res, [], false)
  }

  async function submitClarify() {
    if (busy) return
    setBusy(true)
    setError(null)
    setGenStage(null)
    try {
      const roundAnswers = questions.map((q, i) => ({ question: q, answer: answers[i] || '' }))
      const clarifications = [...priorClarifications, ...roundAnswers]
      const res = await generateRoadmap({ goal, clarifications, skipFollowUp: isFollowUpRound, tier }, setGenStage)
      handleDraftResult(res, clarifications, isFollowUpRound)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  // RB-3: the founder confirmed this is genuinely a new topic — resubmit with skipTopicMatch so
  // the same match prompt doesn't just reappear.
  async function startSeparateGoal() {
    if (busy) return
    setBusy(true)
    setError(null)
    setGenStage(null)
    setTopicMatch(null)
    try {
      const res = await generateRoadmap({ goal, clarifications: null, skipFollowUp: false, tier, skipTopicMatch: true }, setGenStage)
      handleDraftResult(res, [], false)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  function openMatchedRoadmap() {
    if (topicMatch?.candidateRoadmapId) onOpenRoadmap?.(topicMatch.candidateRoadmapId)
  }

  async function proposeSubtopic() {
    if (!topicMatch?.candidateRoadmapId || busy) return
    setBusy(true)
    setError(null)
    try {
      setSubtopicProposal(await proposeSubtopicModule(topicMatch.candidateRoadmapId, goal))
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
      onOpenRoadmap?.(topicMatch.candidateRoadmapId)
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  // MINI: the one place besides the roadmap builder handoff this flow ever navigates — a real
  // roadmap needs its own persistent home (RB-6.4).
  async function acceptFlat() {
    if (!title.trim() || busy) return
    setBusy(true)
    setError(null)
    try {
      const roadmap = await createRoadmap({ title: title.trim(), draftSteps: toDraftSteps(flatSteps), assessment, tier })
      onOpenRoadmap?.(roadmap.id)
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  async function route(intent, input) {
    if (intent === 'DO') {
      const entry = await createEntry({ type: 'task', text: input })
      resolveDone('task', entry.acknowledgment || 'Held.')
      return
    }
    if (intent === 'LEARN' || intent === 'PLAN_A_JOURNEY' || intent === 'PREPARE') {
      await startDraft(input)
      return
    }
    if (intent === 'TROUBLESHOOT') {
      setStage('troubleshoot')
      setTroubleshootAnswer({ question: input, loading: true })
      const res = await explainText(input, {})
      setTroubleshootAnswer({ question: input, answer: res.response })
      return
    }
    if (intent === 'PRACTICE' || intent === 'REVIEW') {
      setStage('practice')
      setPracticeSteps(await listCompletedSteps())
      return
    }
    if (intent === 'EXPLORE' || intent === 'ASSESS') {
      // Honest, not a dead end (RB-5.3/5.4/6.4) — no destination has been designed for these yet.
      resolveDone('explore', "Compass doesn't have a way to help with that yet.")
      return
    }
    // IDEA, or classification failed/unavailable — the original low-friction capture, unchanged.
    const entry = await createEntry({ text: input, significance })
    resolveDone('idea', entry.acknowledgment || 'Held.')
  }

  async function submit() {
    if (!text.trim() || busy) return
    if (speech.listening) speech.stop()
    const input = text.trim()
    setText('')
    setSignificance(null)
    setBusy(true)
    setError(null)
    setStage('loading')
    setGenStage(null)
    try {
      let intent = 'IDEA'
      try {
        intent = (await classifyIntent(input)).intent
      } catch {
        // Best-effort, same discipline as every other AI call in this codebase (CLAUDE.md) — a
        // down/unconfigured provider never blocks capture, it just falls back to plain IDEA.
      }
      await route(intent, input)
    } catch (err) {
      setStage('idle')
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  function onKeyDown(e) {
    // Enter submits; Shift+Enter for a newline.
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      submit()
    }
  }

  const showTextarea = stage === 'idle' || stage === 'done'
  const canSubmit = stage === 'idle' && text.trim().length > 0 && !busy

  return (
    <div className="capture">
      {showTextarea && (
        <div className="capture-field">
          <textarea
            ref={textareaRef}
            className="capture-input"
            value={text}
            onChange={(e) => {
              setText(e.target.value)
              if (error) setError(null)
            }}
            onKeyDown={onKeyDown}
            placeholder={speech.listening ? 'Listening…' : 'Capture a thought, a task, a goal…'}
            autoFocus
            rows={3}
          />
          {speech.listening && speech.interim && <p className="capture-interim">{speech.interim}</p>}
          {stage === 'idle' && ambient && text.trim() && (
            <p className="intake-ambient">
              {INTENT_LABELS[ambient.intent] || ambient.intent.toLowerCase()}
            </p>
          )}
        </div>
      )}

      {stage === 'loading' && (
        <div key="loading" className={`${MORPH_CLASS} intake-loading`}>
          {genStage ? `${GEN_STAGE_LABELS[genStage] || 'Working'}…` : 'figuring out where this goes…'}
        </div>
      )}

      {stage === 'done' && doneMessage && (
        <div key="done" className={`${MORPH_CLASS} intake-done`}>
          <p className="capture-status">{doneMessage.message}</p>
        </div>
      )}

      {stage === 'troubleshoot' && troubleshootAnswer && (
        <div key="troubleshoot" className={`${MORPH_CLASS} capture-troubleshoot`}>
          <p className="capture-troubleshoot-q">{troubleshootAnswer.question}</p>
          <p className="capture-troubleshoot-a">
            {troubleshootAnswer.loading ? 'Thinking…' : troubleshootAnswer.answer}
          </p>
          {!troubleshootAnswer.loading && (
            <div className="intake-card-actions">
              <Button
                variant="primary"
                onClick={() => {
                  pushTrail({ kind: 'troubleshoot', label: troubleshootAnswer.question })
                  cancelCard()
                }}
              >
                Done
              </Button>
            </div>
          )}
        </div>
      )}

      {stage === 'practice' && (
        <div key="practice" className={MORPH_CLASS}>
          {practiceSteps.length === 0 ? (
            <p className="capture-status">No completed steps yet.</p>
          ) : (
            <div className="intake-practice-list">
              {practiceSteps.map((s) => (
                <button
                  key={s.id}
                  type="button"
                  className="intake-practice-chip"
                  onClick={() => setVerifyingStep(s)}
                >
                  {s.content?.text}
                </button>
              ))}
            </div>
          )}
          <div className="intake-card-actions">
            <Button variant="ghost" onClick={cancelCard}>
              Close
            </Button>
          </div>
        </div>
      )}

      {stage === 'topicMatch' && topicMatch && (
        <div key="topicMatch" className={MORPH_CLASS}>
          <p className="gen-lead">{topicMatchLead(topicMatch)}</p>
          <p className="gen-interpretation">{topicMatch.reasoning}</p>

          {subtopicProposal ? (
            <>
              <p className="gen-lead">Add this module to “{topicMatch.candidateName}”?</p>
              <div className="gen-step intake-subtopic-card">
                <strong>{subtopicProposal.title}</strong>
                {subtopicProposal.scope && <p className="intake-subtopic-scope">{subtopicProposal.scope}</p>}
                {subtopicProposal.possibleDuplicate && (
                  <p className="roadmap-error intake-subtopic-warning">This looks similar to a module already there — check before adding.</p>
                )}
              </div>
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
              <Button variant="ghost" onClick={cancelCard} disabled={busy}>
                Cancel
              </Button>
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
        </div>
      )}

      {stage === 'clarify' && (
        <div key="clarify" className={MORPH_CLASS}>
          <p className="gen-lead">{isFollowUpRound ? 'One more thing.' : 'A couple of things first, so the plan fits you.'}</p>
          <div className="gen-questions">
            {questions.map((q, i) => (
              <div className="gen-question" key={i}>
                <label className="gen-question-text">{q}</label>
                <input
                  className="step-input"
                  value={answers[i]}
                  onChange={(e) => setAnswers((prev) => prev.map((a, j) => (j === i ? e.target.value : a)))}
                  autoFocus={i === 0}
                />
              </div>
            ))}
          </div>
          {busy && (
            <p className="gen-progress">{genStage ? `${GEN_STAGE_LABELS[genStage] || 'Working'}…` : 'Working…'}</p>
          )}
          <div className="roadmap-actions">
            {error && <span className="roadmap-error">{error}</span>}
            <Button variant="ghost" onClick={cancelCard} disabled={busy}>
              Cancel
            </Button>
            <Button variant="primary" onClick={submitClarify} disabled={busy}>
              {busy ? 'Working…' : 'Continue'}
            </Button>
          </div>
        </div>
      )}

      {stage === 'flat' && (
        <div key="flat" className={MORPH_CLASS}>
          <input
            className="roadmap-title"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Roadmap title"
          />
          <StepProposalEditor steps={flatSteps} onChange={setFlatSteps} />
          <div className="roadmap-actions">
            {error && <span className="roadmap-error">{error}</span>}
            <Button variant="ghost" onClick={cancelCard} disabled={busy}>
              Cancel
            </Button>
            <Button
              variant="primary"
              onClick={acceptFlat}
              disabled={busy || !title.trim() || !flatSteps.some((s) => s.text.trim())}
            >
              {busy ? 'Creating…' : 'Create roadmap'}
            </Button>
          </div>
        </div>
      )}

      {stage === 'handoff' && (
        <div key="handoff" className={MORPH_CLASS}>
          <p className="gen-lead">This needs real room to plan out — {title || goal}.</p>
          <div className="roadmap-actions">
            <Button variant="ghost" onClick={cancelCard}>
              Cancel
            </Button>
            <Button variant="primary" onClick={() => onOpenRoadmapBuilder?.(goal, handoffResult)}>
              Open full roadmap builder
            </Button>
          </div>
        </div>
      )}

      {stage === 'idle' && (
        <>
          <div className="capture-significance-block">
            {showSigHint && (
              <p className="capture-sig-hint">big things come back later — small ones just get held</p>
            )}
            <div className="capture-significance" role="group" aria-label="How big is this?">
              {['big', 'small'].map((level) => (
                <button
                  key={level}
                  type="button"
                  className={'sig-tap' + (significance === level ? ' is-selected' : '')}
                  aria-pressed={significance === level}
                  title={
                    level === 'big'
                      ? 'Worth returning to — this will resurface later'
                      : 'A passing note — held, not pushed'
                  }
                  onClick={() => setSignificance((cur) => (cur === level ? null : level))}
                >
                  {level}
                </button>
              ))}
            </div>
          </div>

          <div className="capture-footer">
            <span className={'capture-status' + (error ? ' is-error' : '')}>{error}</span>

            {speech.supported && (
              <button
                type="button"
                className={'capture-mic' + (speech.listening ? ' is-active' : '')}
                onClick={speech.toggle}
                aria-pressed={speech.listening}
                aria-label={speech.listening ? 'Stop speaking' : 'Speak instead of typing'}
                title={speech.listening ? 'Stop speaking' : 'Speak instead of typing'}
              >
                <MicIcon />
              </button>
            )}

            <button
              className={'capture-submit' + (canSubmit ? ' is-ready' : '')}
              onClick={submit}
              disabled={!canSubmit}
            >
              Capture
            </button>
          </div>
        </>
      )}

      {stage === 'idle' && trail.length > 0 && (
        <ul className="intake-trail">
          {trail.map((item) => (
            <li key={item.cid} className="intake-trail-item">
              {(item.kind === 'idea' || item.kind === 'task') && onOpenAll ? (
                <button
                  type="button"
                  className="intake-trail-link"
                  onClick={onOpenAll}
                >
                  {TRAIL_PREFIX[item.kind]}: {item.label}
                </button>
              ) : (
                <span>
                  {TRAIL_PREFIX[item.kind] || item.kind}: {item.label}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}

      {verifyingStep && (
        <VerifyModal
          step={verifyingStep}
          onClose={() => {
            pushTrail({ kind: 'practice', label: verifyingStep.content?.text || 'Step reviewed' })
            setVerifyingStep(null)
            cancelCard()
          }}
          onPassed={() => {
            pushTrail({ kind: 'practice', label: verifyingStep.content?.text || 'Step reviewed' })
            setVerifyingStep(null)
            cancelCard()
          }}
          onOverride={() => setVerifyingStep(null)}
          onChanged={() => {}}
        />
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
