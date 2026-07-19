import { useCallback, useEffect, useState } from 'react'
import { getRoadmap, patchEntry, reorderRoadmapSteps } from '../api'
import ProgressBar from './ProgressBar'
import './Roadmap.css'

export default function RoadmapDetail({ id, onBack }) {
  const [roadmap, setRoadmap] = useState(null)
  const [error, setError] = useState(null)
  const [busyStepId, setBusyStepId] = useState(null)
  const [doneNote, setDoneNote] = useState(null)
  const [editingStepId, setEditingStepId] = useState(null)
  const [editText, setEditText] = useState('')
  const [savingEdit, setSavingEdit] = useState(false)

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
      const updated = await patchEntry(stepId, { status: 'done' })
      setDoneNote(updated.acknowledgment || null)
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusyStepId(null)
    }
  }

  async function undoStep(stepId) {
    setBusyStepId(stepId)
    setError(null)
    setDoneNote(null)
    try {
      await patchEntry(stepId, { status: 'captured' })
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusyStepId(null)
    }
  }

  async function moveStep(index, direction) {
    const steps = roadmap.steps
    const target = index + direction
    if (target < 0 || target >= steps.length) return

    const reordered = steps.map((s) => s.id)
    ;[reordered[index], reordered[target]] = [reordered[target], reordered[index]]

    setError(null)
    try {
      await reorderRoadmapSteps(roadmap.id, reordered)
      await load()
    } catch (err) {
      setError(err.message)
    }
  }

  function startEdit(step) {
    setEditingStepId(step.id)
    setEditText(step.content.text || '')
    setError(null)
  }

  function cancelEdit() {
    setEditingStepId(null)
    setEditText('')
  }

  async function saveEdit(stepId) {
    const trimmed = editText.trim()
    if (!trimmed) return
    setSavingEdit(true)
    setError(null)
    try {
      await patchEntry(stepId, { text: trimmed })
      setEditingStepId(null)
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setSavingEdit(false)
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

      {doneNote && <p className="roadmap-done-note">{doneNote}</p>}

      <ol className="step-list">
        {steps.map((step, index) => {
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
          const isEditing = editingStepId === step.id
          return (
            <li key={step.id} className={`step-item ${state}`}>
              <span className="step-marker" aria-hidden="true">
                {isDone ? '✓' : isDropped ? '–' : isCurrent ? '●' : '○'}
              </span>
              {!isEditing && (
                <span className="step-move">
                  <button
                    className="step-move-btn"
                    onClick={() => moveStep(index, -1)}
                    disabled={index === 0}
                    aria-label="Move step up"
                  >
                    ↑
                  </button>
                  <button
                    className="step-move-btn"
                    onClick={() => moveStep(index, 1)}
                    disabled={index === steps.length - 1}
                    aria-label="Move step down"
                  >
                    ↓
                  </button>
                </span>
              )}
              {isEditing ? (
                <input
                  className="step-edit-input"
                  value={editText}
                  onChange={(e) => setEditText(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') saveEdit(step.id)
                    if (e.key === 'Escape') cancelEdit()
                  }}
                  autoFocus
                />
              ) : (
                <span className="step-text">{step.content.text}</span>
              )}
              {isEditing ? (
                <span className="step-edit-actions">
                  <button
                    className="step-edit-btn"
                    onClick={() => saveEdit(step.id)}
                    disabled={savingEdit || !editText.trim()}
                  >
                    {savingEdit ? 'Saving…' : 'Save'}
                  </button>
                  <button
                    className="step-edit-btn"
                    onClick={cancelEdit}
                    disabled={savingEdit}
                  >
                    Cancel
                  </button>
                </span>
              ) : (
                <button className="step-edit-btn" onClick={() => startEdit(step)}>
                  Edit
                </button>
              )}
              {!isEditing && isCurrent && (
                <button
                  className="step-done-btn"
                  onClick={() => markDone(step.id)}
                  disabled={busyStepId === step.id}
                >
                  {busyStepId === step.id ? 'Marking…' : 'Mark done'}
                </button>
              )}
              {!isEditing && isDone && (
                <button
                  className="step-undo-btn"
                  onClick={() => undoStep(step.id)}
                  disabled={busyStepId === step.id}
                >
                  {busyStepId === step.id ? 'Undoing…' : 'Undo'}
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
