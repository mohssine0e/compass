import { Fragment, useCallback, useEffect, useState } from 'react'
import {
  deleteRoadmapStep,
  getRoadmap,
  insertRoadmapStep,
  patchEntry,
  reorderRoadmapSteps,
} from '../api'
import ProgressBar from './ProgressBar'
import StepDeepView from './StepDeepView'
import './Roadmap.css'

export default function RoadmapDetail({ id, onBack }) {
  const [roadmap, setRoadmap] = useState(null)
  const [error, setError] = useState(null)
  const [busyStepId, setBusyStepId] = useState(null)
  const [doneNote, setDoneNote] = useState(null)
  const [editingStepId, setEditingStepId] = useState(null)
  const [editText, setEditText] = useState('')
  const [savingEdit, setSavingEdit] = useState(false)
  const [insertAtIndex, setInsertAtIndex] = useState(null)
  const [insertText, setInsertText] = useState('')
  const [savingInsert, setSavingInsert] = useState(false)
  const [deepStepId, setDeepStepId] = useState(null)

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

  function startInsert(atIndex) {
    setInsertAtIndex(atIndex)
    setInsertText('')
    setError(null)
  }

  function cancelInsert() {
    setInsertAtIndex(null)
    setInsertText('')
  }

  async function submitInsert() {
    const trimmed = insertText.trim()
    if (!trimmed) return
    setSavingInsert(true)
    setError(null)
    try {
      await insertRoadmapStep(roadmap.id, trimmed, insertAtIndex)
      setInsertAtIndex(null)
      setInsertText('')
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setSavingInsert(false)
    }
  }

  async function deleteStep(step) {
    if (!window.confirm(`Delete "${step.content.text}"? This can't be undone.`)) return
    setBusyStepId(step.id)
    setError(null)
    try {
      await deleteRoadmapStep(roadmap.id, step.id)
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusyStepId(null)
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

  function renderInsertRow(atIndex) {
    if (insertAtIndex === atIndex) {
      return (
        <li className="step-insert-row">
          <input
            className="step-edit-input"
            value={insertText}
            onChange={(e) => setInsertText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') submitInsert()
              if (e.key === 'Escape') cancelInsert()
            }}
            placeholder="New step"
            autoFocus
          />
          <span className="step-edit-actions">
            <button
              className="step-edit-btn"
              onClick={submitInsert}
              disabled={savingInsert || !insertText.trim()}
            >
              {savingInsert ? 'Adding…' : 'Add'}
            </button>
            <button className="step-edit-btn" onClick={cancelInsert} disabled={savingInsert}>
              Cancel
            </button>
          </span>
        </li>
      )
    }
    return (
      <li className="step-insert-row">
        <button className="step-insert-btn" onClick={() => startInsert(atIndex)}>
          + Insert step
        </button>
      </li>
    )
  }

  const { title, notes, progress, steps } = roadmap
  // For showing "needs: <step>" on steps that carry a real prerequisite (Phase 7).
  const textById = new Map(steps.map((s) => [s.id, s.content.text]))

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
            <Fragment key={step.id}>
              {renderInsertRow(index)}
              <li className={`step-item ${state}`}>
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
                <span
                  className="step-text step-text-openable"
                  onDoubleClick={() => setDeepStepId(step.id)}
                  title="Double-click for details"
                >
                  {step.content.text}
                  {(step.content.kind === 'project' ||
                    step.content.weight ||
                    (step.dependsOn && textById.get(step.dependsOn))) && (
                    <span className="step-tags">
                      {step.content.kind === 'project' && (
                        <span className="step-tag is-project">project</span>
                      )}
                      {step.content.weight && step.content.weight !== 'medium' && (
                        <span className="step-tag">{step.content.weight}</span>
                      )}
                      {step.dependsOn && textById.get(step.dependsOn) && (
                        <span className="step-needs">needs: {textById.get(step.dependsOn)}</span>
                      )}
                    </span>
                  )}
                </span>
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
                <span className="step-edit-actions">
                  <button className="step-edit-btn" onClick={() => startEdit(step)}>
                    Edit
                  </button>
                  <button
                    className="step-edit-btn step-delete-btn"
                    onClick={() => deleteStep(step)}
                    disabled={busyStepId === step.id}
                  >
                    Delete
                  </button>
                </span>
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
            </Fragment>
          )
        })}
        {renderInsertRow(steps.length)}
      </ol>

      {deepStepId && steps.find((s) => s.id === deepStepId) && (
        <StepDeepView
          step={steps.find((s) => s.id === deepStepId)}
          onClose={() => setDeepStepId(null)}
          onChanged={load}
        />
      )}
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
