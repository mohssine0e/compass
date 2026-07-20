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
import VerifyModal from './VerifyModal'
import { Badge, Button, Menu } from './ui'
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
  const [verifyStepId, setVerifyStepId] = useState(null)
  // Reorder mode (Phase 12): a dedicated drag-to-reorder mode, saved explicitly — no
  // per-step ↑/↓ buttons, no save-on-every-nudge.
  const [reorderMode, setReorderMode] = useState(false)
  const [draftOrder, setDraftOrder] = useState([])
  const [savingOrder, setSavingOrder] = useState(false)
  const [dragIndex, setDragIndex] = useState(null)

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

  // When the step (or its roadmap) is set to be verified, "mark done" goes through the check
  // gate instead of self-reporting (Phase 8).
  function requestMarkDone(step) {
    const mode = (step.content && step.content.verify) || (roadmap && roadmap.verify)
    if (mode === 'light' || mode === 'full') {
      setVerifyStepId(step.id)
    } else {
      markDone(step.id)
    }
  }

  async function setVerifyMode(mode) {
    setError(null)
    try {
      await patchEntry(roadmap.id, { verify: mode })
      await load()
    } catch (err) {
      setError(err.message)
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

  function enterReorder() {
    setDraftOrder(roadmap.steps)
    setReorderMode(true)
    setEditingStepId(null)
    setInsertAtIndex(null)
    setError(null)
  }

  function cancelReorder() {
    setReorderMode(false)
    setDraftOrder([])
    setDragIndex(null)
  }

  // Local-only drag reordering; nothing is saved until "Save order" (Phase 12).
  function onDragEnter(overIndex) {
    if (dragIndex === null || dragIndex === overIndex) return
    setDraftOrder((prev) => {
      const next = [...prev]
      const [moved] = next.splice(dragIndex, 1)
      next.splice(overIndex, 0, moved)
      return next
    })
    setDragIndex(overIndex)
  }

  async function saveOrder() {
    if (savingOrder) return
    setSavingOrder(true)
    setError(null)
    try {
      await reorderRoadmapSteps(roadmap.id, draftOrder.map((s) => s.id))
      setReorderMode(false)
      setDraftOrder([])
      setDragIndex(null)
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setSavingOrder(false)
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

  // The active inline "insert step" input, shown at one position at a time.
  function renderInsertInput(atIndex) {
    if (insertAtIndex !== atIndex) return null
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
          <Button variant="ghost" onClick={submitInsert} disabled={savingInsert || !insertText.trim()}>
            {savingInsert ? 'Adding…' : 'Add'}
          </Button>
          <Button variant="ghost" onClick={cancelInsert} disabled={savingInsert}>
            Cancel
          </Button>
        </span>
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

      <div className="roadmap-toolbar">
        {!reorderMode && (
          <label className="roadmap-verify">
            <span>Check before done</span>
            <select value={roadmap.verify || 'off'} onChange={(e) => setVerifyMode(e.target.value)}>
              <option value="off">off — self-report</option>
              <option value="light">light — quick question</option>
              <option value="full">full — real check</option>
            </select>
          </label>
        )}
        <span className="roadmap-toolbar-spacer" />
        {reorderMode ? (
          <>
            <Button variant="ghost" onClick={cancelReorder} disabled={savingOrder}>
              Cancel
            </Button>
            <Button variant="primary" onClick={saveOrder} disabled={savingOrder}>
              {savingOrder ? 'Saving…' : 'Save order'}
            </Button>
          </>
        ) : (
          steps.length > 1 && (
            <Button variant="ghost" onClick={enterReorder}>
              Reorder
            </Button>
          )
        )}
      </div>

      {doneNote && <p className="roadmap-done-note">{doneNote}</p>}

      {reorderMode ? (
        <ol className="step-list is-reordering">
          {draftOrder.map((step, index) => (
            <li
              key={step.id}
              className={'step-item step-reorder-row' + (dragIndex === index ? ' is-dragging' : '')}
              draggable
              onDragStart={() => setDragIndex(index)}
              onDragEnter={() => onDragEnter(index)}
              onDragOver={(e) => e.preventDefault()}
              onDragEnd={() => setDragIndex(null)}
            >
              <span className="step-drag-handle" aria-hidden="true">⠿</span>
              <span className="step-text">{step.content.text}</span>
            </li>
          ))}
        </ol>
      ) : (
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
            const menuItems = [
              { label: 'Edit', onClick: () => startEdit(step) },
              { label: 'Insert step above', onClick: () => startInsert(index) },
              ...(isDone ? [{ label: 'Undo', onClick: () => undoStep(step.id) }] : []),
              { label: 'Delete', onClick: () => deleteStep(step), danger: true },
            ]
            return (
              <Fragment key={step.id}>
                {renderInsertInput(index)}
                <li className={`step-item ${state}`}>
                  <span className="step-marker" aria-hidden="true">
                    {isDone ? '✓' : isDropped ? '–' : isCurrent ? '●' : '○'}
                  </span>
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
                          {step.content.kind === 'project' && <Badge tone="brass">project</Badge>}
                          {step.content.weight && step.content.weight !== 'medium' && (
                            <Badge>{step.content.weight}</Badge>
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
                      <Button
                        variant="ghost"
                        onClick={() => saveEdit(step.id)}
                        disabled={savingEdit || !editText.trim()}
                      >
                        {savingEdit ? 'Saving…' : 'Save'}
                      </Button>
                      <Button variant="ghost" onClick={cancelEdit} disabled={savingEdit}>
                        Cancel
                      </Button>
                    </span>
                  ) : (
                    <span className="step-actions">
                      {isCurrent && (
                        <Button
                          variant="primary"
                          onClick={() => requestMarkDone(step)}
                          disabled={busyStepId === step.id}
                        >
                          {busyStepId === step.id ? 'Marking…' : 'Mark done'}
                        </Button>
                      )}
                      <Menu items={menuItems} label={`Actions for step ${index + 1}`} />
                    </span>
                  )}
                </li>
              </Fragment>
            )
          })}
          {renderInsertInput(steps.length)}
          <li className="step-insert-row">
            <button className="step-insert-btn" onClick={() => startInsert(steps.length)}>
              + Add step
            </button>
          </li>
        </ol>
      )}

      {deepStepId && steps.find((s) => s.id === deepStepId) && (
        <StepDeepView
          step={steps.find((s) => s.id === deepStepId)}
          onClose={() => setDeepStepId(null)}
          onChanged={load}
        />
      )}

      {verifyStepId && steps.find((s) => s.id === verifyStepId) && (
        <VerifyModal
          step={steps.find((s) => s.id === verifyStepId)}
          onClose={() => setVerifyStepId(null)}
          onPassed={async () => {
            setVerifyStepId(null)
            setDoneNote(null)
            await load()
          }}
          onOverride={async () => {
            const id = verifyStepId
            setVerifyStepId(null)
            await markDone(id)
          }}
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
