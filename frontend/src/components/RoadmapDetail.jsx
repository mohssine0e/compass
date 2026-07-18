import { useCallback, useEffect, useState } from 'react'
import { getRoadmap, patchEntry } from '../api'
import ProgressBar from './ProgressBar'
import './Roadmap.css'

export default function RoadmapDetail({ id, onBack }) {
  const [roadmap, setRoadmap] = useState(null)
  const [error, setError] = useState(null)
  const [busyStepId, setBusyStepId] = useState(null)

  const load = useCallback(async () => {
    try {
      setRoadmap(await getRoadmap(id))
    } catch (err) {
      setError(err.message)
    }
  }, [id])

  useEffect(() => {
    load()
  }, [load])

  async function markDone(stepId) {
    setBusyStepId(stepId)
    setError(null)
    try {
      await patchEntry(stepId, { status: 'done' })
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusyStepId(null)
    }
  }

  if (error) {
    return (
      <div className="roadmap-detail">
        <BackLink onBack={onBack} />
        <p className="roadmap-error">{error}</p>
      </div>
    )
  }

  if (!roadmap) {
    return (
      <div className="roadmap-detail">
        <BackLink onBack={onBack} />
      </div>
    )
  }

  const { title, notes, progress, steps } = roadmap

  return (
    <div className="roadmap-detail">
      <BackLink onBack={onBack} />

      <h1 className="screen-title">{title}</h1>
      {notes && <p className="roadmap-detail-notes">{notes}</p>}

      <div className="roadmap-detail-progress">
        <ProgressBar done={progress.done} total={progress.total} />
        <span className="roadmap-detail-count">
          {progress.currentOrderIndex === null
            ? `All ${progress.total} done.`
            : `${progress.done} of ${progress.total} done`}
        </span>
      </div>

      <ol className="step-list">
        {steps.map((step) => {
          const isCurrent = step.orderIndex === progress.currentOrderIndex
          const isDone = step.status === 'done'
          const isDropped = step.status === 'dropped'
          const state = isDone
            ? 'is-done'
            : isDropped
              ? 'is-dropped'
              : isCurrent
                ? 'is-current'
                : 'is-upcoming'
          return (
            <li key={step.id} className={`step-item ${state}`}>
              <span className="step-marker" aria-hidden="true">
                {isDone ? '✓' : isDropped ? '–' : isCurrent ? '●' : '○'}
              </span>
              <span className="step-text">{step.content.text}</span>
              {isCurrent && (
                <button
                  className="step-done-btn"
                  onClick={() => markDone(step.id)}
                  disabled={busyStepId === step.id}
                >
                  {busyStepId === step.id ? 'Marking…' : 'Mark done'}
                </button>
              )}
            </li>
          )
        })}
      </ol>
    </div>
  )
}

function BackLink({ onBack }) {
  return (
    <button className="back-link" onClick={onBack}>
      ← Roadmaps
    </button>
  )
}
