import { Fragment, useCallback, useEffect, useState } from 'react'
import {
  deleteRoadmap,
  deleteRoadmapStep,
  getRoadmap,
  insertRoadmapStep,
  patchEntry,
  reorderRoadmapSteps,
  setRoadmapArchived,
} from '../api'
import ExpandModuleModal from './ExpandModuleModal'
import LearningPathView from './LearningPathView'
import ProgressBar from './ProgressBar'
import StepDeepView from './StepDeepView'
import VerifyModal from './VerifyModal'
import { Badge, Button, Menu } from './ui'
import './Roadmap.css'

// A roadmap is a tree (Phase 13): a flat roadmap is one level of leaf steps and reads as a plain
// list; a big one nests modules (child roadmaps) and substeps. Progress and "current step" come
// from the backend, rolled up over leaf steps wherever they sit, so this view just renders.
const nodeText = (node) =>
  node.type === 'roadmap' ? node.content?.title : node.content?.text

// Container node ids that are fully complete — collapsed by default so a big roadmap opens
// anchored on the modules still in play.
function fullyDoneGroups(nodes, out = []) {
  for (const n of nodes) {
    if (n.children && n.children.length > 0) {
      if (n.progress && n.progress.total > 0 && n.progress.done === n.progress.total) {
        out.push(n.id)
      }
      fullyDoneGroups(n.children, out)
    }
  }
  return out
}

// Flatten every node's text by id, for the "needs: <step>" prerequisite label across the tree.
function textByIdOf(nodes, map = new Map()) {
  for (const n of nodes) {
    map.set(n.id, nodeText(n))
    if (n.children) textByIdOf(n.children, map)
  }
  return map
}

export default function RoadmapDetail({ id, onBack, onGone }) {
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
  // Reorder mode (Phase 12): drag-to-reorder the top-level nodes, saved explicitly.
  const [reorderMode, setReorderMode] = useState(false)
  const [draftOrder, setDraftOrder] = useState([])
  const [savingOrder, setSavingOrder] = useState(false)
  const [dragIndex, setDragIndex] = useState(null)
  // Which container nodes are collapsed (Phase 13). Seeded from fully-done groups on first load.
  const [collapsed, setCollapsed] = useState(null)
  // For a flat roadmap: collapse the run of completed steps above the current one (Phase 12).
  const [showCompleted, setShowCompleted] = useState(false)
  // The module currently being expanded into steps (Phase 13), or null.
  const [expandingModuleId, setExpandingModuleId] = useState(null)
  // Structural tree vs. the ordered "what's next" learning path (Phase 13).
  const [view, setView] = useState('tree')

  const load = useCallback(async () => {
    try {
      const data = await getRoadmap(id)
      setRoadmap(data)
      setCollapsed((prev) => (prev === null ? new Set(fullyDoneGroups(data.children || [])) : prev))
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
  function requestMarkDone(node) {
    const mode = (node.content && node.content.verify) || (roadmap && roadmap.verify)
    if (mode === 'light' || mode === 'full') {
      setVerifyStepId(node.id)
    } else {
      markDone(node.id)
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
    setDraftOrder(roadmap.children)
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

  // Local-only drag reordering of the top-level nodes; nothing is saved until "Save order".
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

  async function archiveRoadmap() {
    setError(null)
    try {
      await setRoadmapArchived(id, true)
      onGone?.()
    } catch (err) {
      setError(err.message)
    }
  }

  async function deleteWholeRoadmap() {
    if (!window.confirm(`Delete "${roadmap.title}" and all its steps? This can't be undone.`)) return
    setError(null)
    try {
      await deleteRoadmap(id)
      onGone?.()
    } catch (err) {
      setError(err.message)
    }
  }

  async function deleteStep(node) {
    if (!window.confirm(`Delete "${nodeText(node)}"? This can't be undone.`)) return
    setBusyStepId(node.id)
    setError(null)
    try {
      await deleteRoadmapStep(roadmap.id, node.id)
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusyStepId(null)
    }
  }

  function startEdit(node) {
    setEditingStepId(node.id)
    setEditText(nodeText(node) || '')
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

  function toggleCollapsed(nodeId) {
    setCollapsed((prev) => {
      const next = new Set(prev)
      if (next.has(nodeId)) next.delete(nodeId)
      else next.add(nodeId)
      return next
    })
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

  const { title, notes, progress } = roadmap
  const children = roadmap.children || []
  const textById = textByIdOf(children)
  const currentId = progress.currentStepId
  // Long-list anchoring only applies to a flat roadmap (nested ones chunk via modules).
  const isFlat = children.every((c) => !(c.children && c.children.length > 0))
  const currentIdx = children.findIndex((c) => c.id === currentId)
  const completedAbove = isFlat && currentIdx > 0 ? currentIdx : 0
  const collapseCompleted = isFlat && !reorderMode && !showCompleted && completedAbove > 4

  // The active inline "insert step" input, shown at one top-level position at a time.
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

  // A leaf step row.
  function StepRow({ node, depth }) {
    const isCurrent = node.id === currentId
    const isDone = node.status === 'done'
    const isDropped = node.status === 'dropped'
    const state = isDone ? 'is-done' : isDropped ? 'is-dropped' : isCurrent ? 'is-current' : 'is-upcoming'
    const isEditing = editingStepId === node.id
    const menuItems = [
      { label: 'Edit', onClick: () => startEdit(node) },
      ...(depth === 0 ? [{ label: 'Insert step above', onClick: () => startInsert(node.orderIndex) }] : []),
      ...(isDone ? [{ label: 'Undo', onClick: () => undoStep(node.id) }] : []),
      { label: 'Delete', onClick: () => deleteStep(node), danger: true },
    ]
    return (
      <li className={`step-item ${state}`} style={depth ? { marginLeft: depth * 22 } : undefined}>
        <span className="step-marker" aria-hidden="true">
          {isDone ? '✓' : isDropped ? '–' : isCurrent ? '●' : '○'}
        </span>
        {isEditing ? (
          <input
            className="step-edit-input"
            value={editText}
            onChange={(e) => setEditText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') saveEdit(node.id)
              if (e.key === 'Escape') cancelEdit()
            }}
            autoFocus
          />
        ) : (
          <span
            className="step-text step-text-openable"
            onDoubleClick={() => setDeepStepId(node.id)}
            title="Double-click for details"
          >
            {node.content?.text}
            {(node.content?.kind === 'project' ||
              node.content?.weight ||
              (node.dependsOn && textById.get(node.dependsOn))) && (
              <span className="step-tags">
                {node.content?.kind === 'project' && <Badge tone="brass">project</Badge>}
                {node.content?.weight && node.content.weight !== 'medium' && (
                  <Badge>{node.content.weight}</Badge>
                )}
                {node.dependsOn && textById.get(node.dependsOn) && (
                  <span className="step-needs">needs: {textById.get(node.dependsOn)}</span>
                )}
              </span>
            )}
          </span>
        )}
        {isEditing ? (
          <span className="step-edit-actions">
            <Button variant="ghost" onClick={() => saveEdit(node.id)} disabled={savingEdit || !editText.trim()}>
              {savingEdit ? 'Saving…' : 'Save'}
            </Button>
            <Button variant="ghost" onClick={cancelEdit} disabled={savingEdit}>
              Cancel
            </Button>
          </span>
        ) : (
          <span className="step-actions">
            {isCurrent && (
              <Button variant="primary" onClick={() => requestMarkDone(node)} disabled={busyStepId === node.id}>
                {busyStepId === node.id ? 'Marking…' : 'Mark done'}
              </Button>
            )}
            <Menu items={menuItems} label={`Actions for ${nodeText(node)}`} />
          </span>
        )}
      </li>
    )
  }

  // A container node — a module (child roadmap) or a step with substeps. Collapsible, with its
  // own rolled-up progress.
  function GroupNode({ node, depth }) {
    const open = !collapsed.has(node.id)
    const p = node.progress || { done: 0, total: 0 }
    return (
      <>
        <li
          className="node-group"
          style={depth ? { marginLeft: depth * 22 } : undefined}
          onClick={() => toggleCollapsed(node.id)}
        >
          <span className="node-group-caret" aria-hidden="true">{open ? '▾' : '▸'}</span>
          <span className="node-group-title">{nodeText(node)}</span>
          <Badge>{p.done}/{p.total}</Badge>
        </li>
        {open && node.children.map((child) => <NodeRenderer key={child.id} node={child} depth={depth + 1} />)}
      </>
    )
  }

  // A module (child roadmap) that hasn't been expanded into steps yet (Phase 13) — its own row
  // with the module's scope and an explicit "Expand" action, instead of being treated as a leaf.
  function EmptyModuleNode({ node, depth }) {
    return (
      <li className="node-group node-group-empty" style={depth ? { marginLeft: depth * 22 } : undefined}>
        <span className="node-group-caret" aria-hidden="true">·</span>
        <span className="node-group-title">
          {nodeText(node)}
          {node.content?.scope && <span className="node-group-scope"> — {node.content.scope}</span>}
        </span>
        <Button variant="ghost" onClick={() => setExpandingModuleId(node.id)}>
          Expand this module
        </Button>
      </li>
    )
  }

  function NodeRenderer({ node, depth }) {
    if (node.type === 'roadmap') {
      return node.children && node.children.length > 0
        ? <GroupNode node={node} depth={depth} />
        : <EmptyModuleNode node={node} depth={depth} />
    }
    if (node.children && node.children.length > 0) return <GroupNode node={node} depth={depth} />
    return <StepRow node={node} depth={depth} />
  }

  return (
    <div className="roadmap-detail">
      <BackLink onBack={onBack} />

      <h1 className="screen-title">{title}</h1>
      {notes && <p className="roadmap-detail-notes">{notes}</p>}

      <div className="roadmap-detail-progress">
        <ProgressBar done={progress.done} total={progress.total} />
        <span className="roadmap-detail-count">
          {progress.currentStepId === null
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
          <>
            <Button variant={view === 'tree' ? 'primary' : 'ghost'} onClick={() => setView('tree')}>
              Tree
            </Button>
            <Button variant={view === 'path' ? 'primary' : 'ghost'} onClick={() => setView('path')}>
              Path
            </Button>
            {view === 'tree' && children.length > 1 && (
              <Button variant="ghost" onClick={enterReorder}>
                Reorder
              </Button>
            )}
            <Menu
              label="Roadmap actions"
              items={[
                { label: 'Archive', onClick: archiveRoadmap },
                { label: 'Delete roadmap', onClick: deleteWholeRoadmap, danger: true },
              ]}
            />
          </>
        )}
      </div>

      {doneNote && <p className="roadmap-done-note">{doneNote}</p>}

      {view === 'path' && !reorderMode ? (
        <LearningPathView roadmap={roadmap} onOpenStep={setDeepStepId} />
      ) : reorderMode ? (
        <ol className="step-list is-reordering">
          {draftOrder.map((node, index) => (
            <li
              key={node.id}
              className={'step-item step-reorder-row' + (dragIndex === index ? ' is-dragging' : '')}
              draggable
              onDragStart={() => setDragIndex(index)}
              onDragEnter={() => onDragEnter(index)}
              onDragOver={(e) => e.preventDefault()}
              onDragEnd={() => setDragIndex(null)}
            >
              <span className="step-drag-handle" aria-hidden="true">⠿</span>
              <span className="step-text">{nodeText(node)}</span>
            </li>
          ))}
        </ol>
      ) : (
        <ol className="step-list">
          {collapseCompleted && (
            <li className="step-collapsed-row">
              <button className="step-collapsed-btn" onClick={() => setShowCompleted(true)}>
                ↑ Show {completedAbove} completed step{completedAbove === 1 ? '' : 's'}
              </button>
            </li>
          )}
          {isFlat && showCompleted && completedAbove > 4 && (
            <li className="step-collapsed-row">
              <button className="step-collapsed-btn" onClick={() => setShowCompleted(false)}>
                Hide completed steps
              </button>
            </li>
          )}
          {children.map((node, index) => {
            if (collapseCompleted && index < currentIdx) return null
            return (
              <Fragment key={node.id}>
                {renderInsertInput(node.orderIndex)}
                <NodeRenderer node={node} depth={0} />
              </Fragment>
            )
          })}
          {renderInsertInput(children.length)}
          <li className="step-insert-row">
            <button className="step-insert-btn" onClick={() => startInsert(children.length)}>
              + Add step
            </button>
          </li>
        </ol>
      )}

      {deepStepId && findNode(children, deepStepId) && (
        <StepDeepView
          step={toStepShape(findNode(children, deepStepId))}
          onClose={() => setDeepStepId(null)}
          onChanged={load}
        />
      )}

      {verifyStepId && findNode(children, verifyStepId) && (
        <VerifyModal
          step={toStepShape(findNode(children, verifyStepId))}
          onClose={() => setVerifyStepId(null)}
          onPassed={async () => {
            setVerifyStepId(null)
            setDoneNote(null)
            await load()
          }}
          onOverride={async () => {
            const target = verifyStepId
            setVerifyStepId(null)
            await markDone(target)
          }}
        />
      )}

      {expandingModuleId && findNode(children, expandingModuleId) && (
        <ExpandModuleModal
          roadmapId={id}
          module={findNode(children, expandingModuleId)}
          onClose={() => setExpandingModuleId(null)}
          onApplied={async () => {
            setExpandingModuleId(null)
            await load()
          }}
        />
      )}
    </div>
  )
}

// StepDeepView / VerifyModal were written against a flat entry ({ id, content, status }); a tree
// node carries the same fields, so pass it through directly.
function toStepShape(node) {
  return node
}

function findNode(nodes, targetId) {
  for (const n of nodes) {
    if (n.id === targetId) return n
    if (n.children) {
      const found = findNode(n.children, targetId)
      if (found) return found
    }
  }
  return null
}

function BackLink({ onBack }) {
  return (
    <button className="back-link" onClick={onBack}>
      ← Roadmaps
    </button>
  )
}
