import { Fragment, useCallback, useEffect, useState } from 'react'
import {
  applyReplan,
  applyReTierProposal,
  applyReformulate,
  applyTopicAddition,
  checkCareerCompletion,
  deleteRoadmap,
  deleteRoadmapStep,
  flattenStep,
  getCanonicalTopicForRoadmap,
  getModulePrefetchStatus,
  getRoadmap,
  graduateStep,
  insertRoadmapStep,
  insertModule,
  patchEntry,
  proposeNewModule,
  proposeReformulate,
  regenerateModuleScope,
  replanModules,
  reorderRoadmapSteps,
  reTierRoadmap,
  setRoadmapArchived,
  suggestTopicAddition,
  syncStepCompletion,
  updateModule,
} from '../api'
import StepProposalEditor, { fromProposedSteps, toDraftSteps } from './StepProposalEditor'
import ExpandModuleModal from './ExpandModuleModal'
import ExpandModulesBatchModal from './ExpandModulesBatchModal'
import LearningPathView from './LearningPathView'
import ProjectsView from './ProjectsView'
import ModuleProposalModal from './ModuleProposalModal'
import ProgressBar from './ProgressBar'
import ReplanModulesModal from './ReplanModulesModal'
import { truncateAtWord } from '../text'
import StepDeepView from './StepDeepView'
import VerifyModal from './VerifyModal'
import {
  Badge,
  Button,
  IconArchive,
  IconDelete,
  IconEdit,
  IconModule,
  IconStep,
  IconSubstep,
  IconSubSubstep,
  IconUndo,
  Menu,
  Modal,
  TextArea,
} from './ui'

// One icon per nesting depth (Phase 21) — module boldest, sub-substep faintest.
const DEPTH_ICONS = [IconModule, IconStep, IconSubstep, IconSubSubstep]
import './Roadmap.css'

// RB-2.5: the re-tier escape hatch's target choices, offered minus whatever the roadmap's
// current tier already is.
const RE_TIER_OPTIONS = ['TASK', 'MINI', 'TOPIC', 'CAREER']

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

// Collapse every container except the one the current step is actually inside — a nested
// roadmap opens showing just the module you're in, right now (Phase 21 / RB-4.10's CAREER
// default). Falls back to collapsing what's fully complete if there's no current step (e.g.
// everything's already done).
function collapseToCurrentModule(data) {
  const children = data.children || []
  if (data.progress?.currentStepId == null) {
    return new Set(fullyDoneGroups(children))
  }
  const groups = []
  const collectGroups = (nodes) => {
    for (const n of nodes) {
      if (n.children && n.children.length > 0) {
        groups.push(n.id)
        collectGroups(n.children)
      }
    }
  }
  collectGroups(children)
  const onPath = new Set(
    (findNodePath(children, data.progress.currentStepId) || []).map((n) => n.id)
  )
  return new Set(groups.filter((gid) => !onPath.has(gid)))
}

// Default collapse on first load, per tier (RB-4.10) — TASK/MINI are flat by nature so there's
// nothing to collapse either way; TOPIC only auto-collapses what's fully done; CAREER anchors to
// the current module. Unknown tier (a roadmap from before RB-2, or a failed classification)
// falls back to today's pre-RB-4 behavior. The founder's own manual choices (RB-4.10's
// `collapseOverrides`, persisted server-side) always win over whatever the default computed.
function seedCollapsed(data) {
  const children = data.children || []
  let base
  if (data.shape !== 'nested') {
    base = new Set()
  } else if (data.tier === 'TOPIC') {
    base = new Set(fullyDoneGroups(children))
  } else if (data.tier === 'CAREER' || data.tier == null) {
    base = collapseToCurrentModule(data)
  } else {
    base = new Set()
  }
  for (const [idStr, isCollapsed] of Object.entries(data.collapseOverrides || {})) {
    const id = Number(idStr)
    if (isCollapsed) base.add(id)
    else base.delete(id)
  }
  return base
}

// True if any module anywhere in the tree has no steps of its own yet — worth polling
// background-draft status for. Once every module's expanded, this goes false and polling stops.
function hasEmptyModule(nodes) {
  for (const n of nodes) {
    if (n.type === 'roadmap' && (!n.children || n.children.length === 0)) return true
    if (n.children && n.children.length > 0 && hasEmptyModule(n.children)) return true
  }
  return false
}

// RB-4.9: node + its direct parent id, by id, across the whole tree — lets a dependency edge be
// checked for "done?" and "same module as the step that depends on it?" without a server round
// trip (everything needed is already in the loaded roadmap tree).
function nodeIndexOf(nodes, parentId = null, map = new Map()) {
  for (const n of nodes) {
    map.set(n.id, { node: n, parentId })
    if (n.children) nodeIndexOf(n.children, n.id, map)
  }
  return map
}

// Only real step->step dependencies count for blocking — a step depending on something from a
// different module is a reminder, never a gate (RB-4.9).
function dependencyInfo(node, nodeIndex) {
  if (!node.dependsOn) return null
  const dep = nodeIndex.get(node.dependsOn)
  const self = nodeIndex.get(node.id)
  if (!dep || !self) return null
  return {
    text: nodeText(dep.node),
    done: dep.node.status === 'done',
    crossModule: dep.parentId !== self.parentId,
  }
}

// The estimated-time rollup (Phase 18) is minutes; render it the way the resource estimates
// that feed it are already written ("~1h 30 min", "~45 min").
function formatMinutes(total) {
  const hours = Math.floor(total / 60)
  const minutes = total % 60
  if (hours === 0) return `${minutes} min`
  if (minutes === 0) return `${hours}h`
  return `${hours}h ${minutes} min`
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
  // Modules picked for a batch expansion (Phase 19) — an explicit, opt-in action; the default
  // stays expanding one module at a time. A Set of module ids, or the batch modal's module list
  // once the founder confirms.
  const [selectedModuleIds, setSelectedModuleIds] = useState(new Set())
  const [batchExpanding, setBatchExpanding] = useState(null)
  // The module currently being redrafted (Phase 18: "regenerate this module"), or null.
  const [regeneratingModuleId, setRegeneratingModuleId] = useState(null)
  // Whether a new module is being drafted to insert (Phase 18), or null.
  const [insertingModule, setInsertingModule] = useState(false)
  // Whether the remaining unexpanded modules are being replanned (Phase 18), or null.
  const [replanning, setReplanning] = useState(false)
  // Structural tree vs. the ordered "what's next" learning path (Phase 13).
  const [view, setView] = useState('tree')
  // Background-draft status per unexpanded module id, e.g. {status, result, error} — see
  // getModulePrefetchStatus. Every unexpanded module starts drafting server-side the moment it
  // appears, so this is usually already DONE by the time the founder opens one.
  const [prefetch, setPrefetch] = useState({})

  // The re-tier escape hatch (RB-2.5): null, or a pending AI-drafted proposal the founder must
  // confirm/cancel before anything actually changes ({ kind: 'regroup'|'arc_order', groups }).
  const [reTierProposal, setReTierProposal] = useState(null)
  const [reTierTarget, setReTierTarget] = useState(null)
  const [reTiering, setReTiering] = useState(false)

  // Topic evolution (RB-3.10): the canonical topic this roadmap was created as/from, if it has
  // one — null while unknown/loading, false once confirmed there isn't one.
  const [canonicalTopic, setCanonicalTopic] = useState(null)
  const [suggestingAddition, setSuggestingAddition] = useState(false)
  const [additionSuggestion, setAdditionSuggestion] = useState('')
  const [additionProposal, setAdditionProposal] = useState(null)
  const [additionBusy, setAdditionBusy] = useState(false)

  // The one-time CAREER completion reflection (RB-4.7), shown once when it first arrives.
  const [careerReflection, setCareerReflection] = useState(null)

  // Break-down review (RB-4.8) — same propose/apply reformulation endpoint the resurfacing flow
  // already uses, now also triggered directly from any step here. null when not open.
  const [breakDownStep, setBreakDownStep] = useState(null)
  const [breakDownSteps, setBreakDownSteps] = useState([])
  const [breakDownBusy, setBreakDownBusy] = useState(false)

  const load = useCallback(async () => {
    try {
      const data = await getRoadmap(id)
      setRoadmap(data)
      setCollapsed((prev) => (prev === null ? seedCollapsed(data) : prev))
    } catch (err) {
      setError(err.message)
    }
  }, [id])

  useEffect(() => {
    load()
  }, [load])

  useEffect(() => {
    let alive = true
    getCanonicalTopicForRoadmap(id)
      .then((topic) => alive && setCanonicalTopic(topic || false))
      .catch(() => alive && setCanonicalTopic(false)) // best-effort — just hides the action
    return () => {
      alive = false
    }
  }, [id])

  // Poll background-draft status while any module here is still unexpanded — stops on its own
  // once every module has steps (hasEmptyModule goes false and the interval is never set again).
  useEffect(() => {
    if (!roadmap || !hasEmptyModule(roadmap.children || [])) return
    let alive = true
    const tick = () => {
      getModulePrefetchStatus(roadmap.id)
        .then((list) => {
          if (!alive) return
          const map = {}
          for (const item of list) map[item.moduleId] = item
          setPrefetch(map)
        })
        .catch(() => {}) // best-effort status only — a failed poll just tries again next tick
    }
    tick()
    const interval = setInterval(tick, 2500)
    return () => {
      alive = false
      clearInterval(interval)
    }
  }, [roadmap])

  // A module already tracked here (drafting or done in the background) shouldn't also be picked
  // for the manual batch-expand action — that would spend a second real AI call on the same
  // content. Drop it from the selection the moment a background job appears for it.
  useEffect(() => {
    setSelectedModuleIds((prev) => {
      const next = new Set([...prev].filter((moduleId) => !prefetch[moduleId]))
      return next.size === prev.size ? prev : next
    })
  }, [prefetch])

  async function markDone(stepId) {
    setBusyStepId(stepId)
    setError(null)
    try {
      const updated = await patchEntry(stepId, { status: 'done' })
      setDoneNote(updated.acknowledgment || null)
      // RB-4.8/4.12: roll completion up/down (substeps <-> parent step, steps -> module) before
      // reloading, so the refreshed tree already reflects it — best-effort, never blocks.
      await syncStepCompletion(stepId).catch(() => {})
      await load()
      // RB-4.7: cheap no-op unless this was the roadmap's last remaining step and it's CAREER
      // tier — only worth asking at all for that tier, so gate the call itself.
      if (roadmap?.tier === 'CAREER') {
        checkCareerCompletion(roadmap.id)
          .then((res) => res?.reflection && setCareerReflection(res.reflection))
          .catch(() => {}) // best-effort — a missed reflection isn't worth surfacing an error for
      }
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

  // RB-2.5: the classifier got this roadmap's scale wrong — a founder-triggered correction,
  // never automatic. Some moves apply right away; others come back as a proposal to review first.
  async function reTier(tier) {
    if (reTiering) return
    setReTiering(true)
    setError(null)
    try {
      const res = await reTierRoadmap(id, tier)
      if (res.status === 'proposal') {
        setReTierTarget(tier)
        setReTierProposal(res.proposal)
      } else if (res.taskEntryId) {
        // Converted to a task — this roadmap is archived now, nothing left to show here.
        onGone?.()
      } else {
        await load()
      }
    } catch (err) {
      setError(err.message)
    } finally {
      setReTiering(false)
    }
  }

  async function confirmReTierProposal() {
    if (!reTierProposal || reTiering) return
    setReTiering(true)
    setError(null)
    try {
      await applyReTierProposal(id, reTierProposal.kind, reTierProposal.groups)
      setReTierProposal(null)
      setReTierTarget(null)
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setReTiering(false)
    }
  }

  function cancelReTierProposal() {
    setReTierProposal(null)
    setReTierTarget(null)
  }

  // Topic evolution (RB-3.10): the founder suggests a specific addition to the canonical topic
  // this roadmap was created from — same propose/confirm pattern as everywhere else.
  async function draftAddition() {
    if (!canonicalTopic || !additionSuggestion.trim() || additionBusy) return
    setAdditionBusy(true)
    setError(null)
    try {
      setAdditionProposal(await suggestTopicAddition(canonicalTopic.id, additionSuggestion.trim()))
    } catch (err) {
      setError(err.message)
    } finally {
      setAdditionBusy(false)
    }
  }

  async function confirmAddition() {
    if (!canonicalTopic || !additionProposal || additionBusy) return
    setAdditionBusy(true)
    setError(null)
    try {
      const updated = await applyTopicAddition(canonicalTopic.id, additionProposal.field, additionProposal.value)
      setCanonicalTopic(updated)
      closeAdditionPrompt()
    } catch (err) {
      setError(err.message)
    } finally {
      setAdditionBusy(false)
    }
  }

  function closeAdditionPrompt() {
    setSuggestingAddition(false)
    setAdditionSuggestion('')
    setAdditionProposal(null)
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

  // "Promote back up" (Phase 20): flatten deletes a container step's substeps (only allowed with
  // no real progress on them, enforced server-side); graduate reparents a substep to be its
  // parent's sibling instead of nested beneath it. Neither is AI-generated, so no propose/approve
  // round trip — just a direct action, same as delete.
  async function flattenStepAction(node) {
    if (!window.confirm(`Remove the substeps under "${nodeText(node)}" and make it a plain step again?`)) return
    setBusyStepId(node.id)
    setError(null)
    try {
      await flattenStep(roadmap.id, node.id)
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusyStepId(null)
    }
  }

  async function graduateStepAction(node) {
    setBusyStepId(node.id)
    setError(null)
    try {
      await graduateStep(roadmap.id, node.id)
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusyStepId(null)
    }
  }

  // RB-4.8: break down any leaf step, any tier — reuses the same reformulate propose/apply
  // endpoint the resurfacing flow already calls for a stalled step.
  async function startBreakDown(node) {
    setBusyStepId(node.id)
    setError(null)
    try {
      const proposal = await proposeReformulate(node.id, 'break_down')
      setBreakDownStep(node)
      setBreakDownSteps(fromProposedSteps(proposal.steps))
    } catch (err) {
      setError(err.message)
    } finally {
      setBusyStepId(null)
    }
  }

  async function confirmBreakDown() {
    if (!breakDownStep || breakDownBusy) return
    setBreakDownBusy(true)
    setError(null)
    try {
      await applyReformulate(breakDownStep.id, { kind: 'break_down', draftSteps: toDraftSteps(breakDownSteps) })
      setBreakDownStep(null)
      setBreakDownSteps([])
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setBreakDownBusy(false)
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

  // RB-4.10: the founder's manual collapse/expand choice always wins over the tier default, and
  // persists (content.collapseOverrides) so it survives a reload — recorded explicitly on every
  // toggle rather than only when it disagrees with the default, so it stays correct even if the
  // default's own answer later changes (e.g. a module finishes).
  function toggleCollapsed(nodeId) {
    setCollapsed((prev) => {
      const next = new Set(prev)
      const nowCollapsed = !next.has(nodeId)
      if (nowCollapsed) next.add(nodeId)
      else next.delete(nodeId)
      const overrides = { ...(roadmap.collapseOverrides || {}), [nodeId]: nowCollapsed }
      patchEntry(roadmap.id, { collapseOverrides: overrides }).catch(() => {})
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
  const nodeIndex = nodeIndexOf(children)
  // Every step, roadmap-wide, for the dependency picker (RB-4.9) — a founder can link across
  // modules on purpose; whether it blocks completion is decided separately (dependencyInfo).
  const allSteps = [...nodeIndex.values()]
    .filter(({ node }) => node.type === 'roadmap_step')
    .map(({ node }) => ({ id: node.id, text: truncateAtWord(nodeText(node), 60) }))
  const currentId = progress.currentStepId
  // Long-list anchoring only applies to a flat roadmap (nested ones chunk via modules).
  const isFlat = children.every((c) => !(c.children && c.children.length > 0))
  // Whether this roadmap's top level is modules rather than plain steps (Phase 18: gates the
  // "insert a module" affordance, distinct from "+ Add step").
  const hasModules = children.some((c) => c.type === 'roadmap')
  // At least one module isn't expanded yet (Phase 18: gates "Replan remaining modules" — there's
  // nothing to redraft once every module already has steps).
  const hasRemainingModules = children.some((c) => c.type === 'roadmap' && c.children.length === 0)
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

  // A leaf step row. A substep (its parent is itself a step, not a module/root) gets a
  // "Graduate" action to promote back up as its parent's sibling instead of nested (Phase 20).
  function StepRow({ node, depth, parentType }) {
    const isCurrent = node.id === currentId
    const isDone = node.status === 'done'
    const isDropped = node.status === 'dropped'
    const state = isDone ? 'is-done' : isDropped ? 'is-dropped' : isCurrent ? 'is-current' : 'is-upcoming'
    const isEditing = editingStepId === node.id
    // RB-4.9: a same-module dependency that isn't done yet blocks completion; a cross-module one
    // is a reminder only — the founder can still complete the step regardless.
    const dep = dependencyInfo(node, nodeIndex)
    const blocked = Boolean(dep && !dep.done && !dep.crossModule)
    const menuItems = [
      { label: 'Edit', onClick: () => startEdit(node), icon: <IconEdit /> },
      { label: 'Break down', onClick: () => startBreakDown(node) },
      ...(depth === 0 ? [{ label: 'Insert step above', onClick: () => startInsert(node.orderIndex) }] : []),
      ...(parentType === 'roadmap_step'
        ? [{ label: 'Graduate (move up a level)', onClick: () => graduateStepAction(node) }]
        : []),
      ...(isDone ? [{ label: 'Undo', onClick: () => undoStep(node.id), icon: <IconUndo /> }] : []),
      { label: 'Delete', onClick: () => deleteStep(node), danger: true, icon: <IconDelete /> },
    ]
    return (
      <li
        className={`step-item ${state} depth-${Math.min(depth, 3)}`}
        style={depth ? { marginLeft: depth * 30 } : undefined}
      >
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
            <span className="step-text-main">{truncateAtWord(node.content?.text)}</span>
            {(node.content?.kind === 'project' ||
              node.content?.weight ||
              node.content?.skeletonOnly ||
              dep) && (
              <span className="step-tags">
                {node.content?.skeletonOnly && (
                  <Badge tone="danger" title="Every AI provider was unavailable when this was drafted — details fill in on their own once one recovers.">
                    basic outline — details pending
                  </Badge>
                )}
                {node.content?.kind === 'project' && <Badge tone="brass">project</Badge>}
                {node.content?.weight && node.content.weight !== 'medium' && (
                  <Badge>{node.content.weight}</Badge>
                )}
                {dep && (
                  <span
                    className={'step-needs' + (blocked ? ' is-blocked' : '')}
                    title={
                      blocked
                        ? `Blocked until "${dep.text}" is done`
                        : dep.crossModule
                          ? `Depends on "${dep.text}" from a different module — reminder only, not a blocker`
                          : undefined
                    }
                  >
                    {blocked ? '🔒' : '⛓️'} needs: {dep.text}
                  </span>
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
              <Button
                variant="primary"
                onClick={() => requestMarkDone(node)}
                disabled={busyStepId === node.id || blocked}
                title={blocked ? `Blocked until "${dep.text}" is done` : undefined}
              >
                {busyStepId === node.id ? 'Marking…' : blocked ? 'Blocked' : 'Mark done'}
              </Button>
            )}
            <Menu items={menuItems} label={`Actions for ${nodeText(node)}`} />
          </span>
        )}
      </li>
    )
  }

  // A container node — a module (child roadmap) or a step with substeps. Collapsible, with its
  // own rolled-up progress. A step-turned-container (from a break-down) gets a "Flatten" action
  // to promote back up (Phase 20) — modules use a different mechanism (expand), not this.
  function GroupNode({ node, depth, parentType }) {
    const open = !collapsed.has(node.id)
    const p = node.progress || { done: 0, total: 0 }
    const isStepContainer = node.type === 'roadmap_step'
    const DepthIcon = DEPTH_ICONS[Math.min(depth, DEPTH_ICONS.length - 1)]
    return (
      <>
        <li
          className={`node-group depth-${Math.min(depth, 3)}`}
          style={depth ? { marginLeft: depth * 30 } : undefined}
          onClick={() => toggleCollapsed(node.id)}
        >
          <span className="node-group-caret" aria-hidden="true">{open ? '▾' : '▸'}</span>
          <span className="node-group-depth-icon" aria-hidden="true">
            <DepthIcon size={14} />
          </span>
          <span className="node-group-title">{nodeText(node)}</span>
          {node.missingProjectFlag && (
            <Badge tone="danger" title="Career-scale roadmap, no project step here yet">
              no project step
            </Badge>
          )}
          <Badge>{p.done}/{p.total}</Badge>
          {isStepContainer && (
            <span onClick={(e) => e.stopPropagation()}>
              <Menu
                label={`Actions for ${nodeText(node)}`}
                items={[{ label: 'Flatten (remove substeps)', onClick: () => flattenStepAction(node), danger: true }]}
              />
            </span>
          )}
        </li>
        {open && node.children.map((child) => (
          <NodeRenderer key={child.id} node={child} depth={depth + 1} parentType={node.type} />
        ))}
      </>
    )
  }

  // A module (child roadmap) that hasn't been expanded into steps yet (Phase 13) — its own row
  // with the module's scope and an explicit "Expand" action, instead of being treated as a leaf.
  // Most modules are already drafting (or done) in the background from the moment they appear —
  // reflect that instead of always offering a button that blocks on a fresh AI call.
  function EmptyModuleNode({ node, depth }) {
    const selected = selectedModuleIds.has(node.id)
    const job = prefetch[node.id]
    const isPending = job?.status === 'PENDING'
    const isDone = job?.status === 'DONE'
    const stepCount = isDone ? (job.result?.steps?.length ?? 0) : 0
    return (
      <li className="node-group node-group-empty" style={depth ? { marginLeft: depth * 30 } : undefined}>
        <input
          type="checkbox"
          className="node-group-select"
          checked={selected}
          disabled={!!job}
          title={job ? 'Already drafting in the background — no need to batch-select this one' : undefined}
          aria-label={`Select ${nodeText(node)} for batch expansion`}
          onChange={(e) => {
            setSelectedModuleIds((prev) => {
              const next = new Set(prev)
              if (e.target.checked) next.add(node.id)
              else next.delete(node.id)
              return next
            })
          }}
        />
        <span className="node-group-caret" aria-hidden="true">·</span>
        <span className="node-group-title">
          {nodeText(node)}
          {node.content?.scope && <span className="node-group-scope"> — {node.content.scope}</span>}
        </span>
        <Button variant="ghost" onClick={() => setRegeneratingModuleId(node.id)}>
          Regenerate scope
        </Button>
        {isPending ? (
          <span className="node-group-working" aria-live="polite">Working on it…</span>
        ) : (
          <Button variant="ghost" onClick={() => setExpandingModuleId(node.id)}>
            {isDone ? `Review ${stepCount} step${stepCount === 1 ? '' : 's'}` : 'Expand this module'}
          </Button>
        )}
      </li>
    )
  }

  function NodeRenderer({ node, depth, parentType }) {
    if (node.type === 'roadmap') {
      return node.children && node.children.length > 0
        ? <GroupNode node={node} depth={depth} parentType={parentType} />
        : <EmptyModuleNode node={node} depth={depth} />
    }
    if (node.children && node.children.length > 0) {
      return <GroupNode node={node} depth={depth} parentType={parentType} />
    }
    return <StepRow node={node} depth={depth} parentType={parentType} />
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
          {progress.estimatedTotalMinutes > 0 &&
            // RB-4.6: a simple qualifier, not a real min/typical/max spread — "expect more if
            // new to this" matters most for a CAREER-scale time commitment; falls back to the
            // structural nested/flat signal when the tier itself isn't known.
            ` · ~${formatMinutes(progress.estimatedTotalMinutes)}${
              (roadmap.tier ? roadmap.tier !== 'MINI' : roadmap.shape === 'nested')
                ? ', expect more if new to this'
                : ''
            }`}
        </span>
      </div>

      {hasRemainingModules && progress.paceMultiplier != null && progress.paceMultiplier > 2 && (
        <p className="roadmap-pace-note">
          At your current pace, this will take roughly {Math.round(progress.paceMultiplier * 10) / 10}x
          longer than estimated.{' '}
          <button className="roadmap-pace-action" onClick={() => setReplanning(true)}>
            Redraft the remaining modules for where you actually are?
          </button>
        </p>
      )}
      {hasRemainingModules && progress.paceMultiplier != null && progress.paceMultiplier < 0.5 && (
        <p className="roadmap-pace-note">
          At your current pace, this is going roughly {Math.round((1 / progress.paceMultiplier) * 10) / 10}x
          faster than estimated.{' '}
          <button className="roadmap-pace-action" onClick={() => setReplanning(true)}>
            Redraft the remaining modules for where you actually are?
          </button>
        </p>
      )}

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
            {roadmap.archetype === 'career_path' && (
              <Button variant={view === 'projects' ? 'primary' : 'ghost'} onClick={() => setView('projects')}>
                Projects
              </Button>
            )}
            {view === 'tree' && children.length > 1 && (
              <Button variant="ghost" onClick={enterReorder}>
                Reorder
              </Button>
            )}
            <Menu
              label="Roadmap actions"
              items={[
                ...RE_TIER_OPTIONS.filter((t) => t !== roadmap.tier).map((t) => ({
                  label: `Re-tier to ${t}`,
                  onClick: () => reTier(t),
                })),
                ...(canonicalTopic
                  ? [{ label: 'Suggest a topic addition', onClick: () => setSuggestingAddition(true) }]
                  : []),
                { label: 'Archive', onClick: archiveRoadmap, icon: <IconArchive /> },
                { label: 'Delete roadmap', onClick: deleteWholeRoadmap, danger: true, icon: <IconDelete /> },
              ]}
            />
          </>
        )}
      </div>

      {doneNote && <p className="roadmap-done-note">{doneNote}</p>}

      {selectedModuleIds.size >= 2 && !reorderMode && (
        <div className="roadmap-batch-bar">
          <span>{selectedModuleIds.size} modules selected</span>
          <Button variant="ghost" onClick={() => setSelectedModuleIds(new Set())}>
            Clear
          </Button>
          <Button
            variant="primary"
            onClick={() => {
              const picked = [...selectedModuleIds]
                .map((id) => findNode(children, id))
                .filter(Boolean)
              setBatchExpanding(picked)
            }}
          >
            Expand {selectedModuleIds.size} selected
          </Button>
        </div>
      )}

      {view === 'path' && !reorderMode ? (
        <LearningPathView roadmap={roadmap} onOpenStep={setDeepStepId} />
      ) : view === 'projects' && !reorderMode ? (
        <ProjectsView roadmap={roadmap} onChanged={load} onOpenStep={setDeepStepId} />
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
                <NodeRenderer node={node} depth={0} parentType="roadmap" />
              </Fragment>
            )
          })}
          {renderInsertInput(children.length)}
          <li className="step-insert-row">
            {hasModules ? (
              <>
                <button className="step-insert-btn" onClick={() => setInsertingModule(true)}>
                  + Insert a module
                </button>
                {hasRemainingModules && (
                  <button className="step-insert-btn" onClick={() => setReplanning(true)}>
                    Replan remaining modules
                  </button>
                )}
              </>
            ) : (
              <button className="step-insert-btn" onClick={() => startInsert(children.length)}>
                + Add step
              </button>
            )}
          </li>
        </ol>
      )}

      {deepStepId && findNode(children, deepStepId) && (
        <StepDeepView
          step={toStepShape(findNode(children, deepStepId))}
          atMaxDepth={(findNodeDepth(children, deepStepId) ?? 0) >= MAX_STEP_DEPTH}
          allSteps={allSteps}
          dependencyCrossModule={Boolean(dependencyInfo(findNode(children, deepStepId), nodeIndex)?.crossModule)}
          breadcrumb={[
            { id: null, label: roadmap.title },
            ...(findNodePath(children, deepStepId) || []).map((n) => ({
              id: n.id,
              type: n.type,
              label: nodeText(n),
            })),
            { id: deepStepId, label: truncateAtWord(nodeText(findNode(children, deepStepId)), 60) },
          ]}
          onNavigate={(seg) => {
            // Root or a module goes back to the tree (expanding that module); an ancestor
            // step opens its own deep view instead.
            if (seg.id != null && seg.type === 'roadmap_step') {
              setDeepStepId(seg.id)
              return
            }
            setDeepStepId(null)
            if (seg.id != null) {
              setCollapsed((prev) => {
                const next = new Set(prev)
                next.delete(seg.id)
                return next
              })
            }
          }}
          onClose={() => setDeepStepId(null)}
          onChanged={load}
        />
      )}

      {verifyStepId && findNode(children, verifyStepId) && (
        <VerifyModal
          step={toStepShape(findNode(children, verifyStepId))}
          onClose={() => setVerifyStepId(null)}
          onChanged={load}
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
          prefetched={
            prefetch[expandingModuleId]?.status === 'DONE' ? prefetch[expandingModuleId].result : undefined
          }
          onClose={() => setExpandingModuleId(null)}
          onApplied={async () => {
            setExpandingModuleId(null)
            await load()
          }}
        />
      )}

      {batchExpanding && (
        <ExpandModulesBatchModal
          roadmapId={id}
          modules={batchExpanding}
          onClose={() => {
            setBatchExpanding(null)
            setSelectedModuleIds(new Set())
          }}
          onApplied={load}
        />
      )}

      {regeneratingModuleId && (
        <ModuleProposalModal
          title="Regenerate this module"
          roadmapId={id}
          draft={() => regenerateModuleScope(id, regeneratingModuleId)}
          accept={(roadmapId, moduleTitle, scope) => updateModule(roadmapId, regeneratingModuleId, moduleTitle, scope)}
          onClose={() => setRegeneratingModuleId(null)}
          onApplied={async () => {
            setRegeneratingModuleId(null)
            await load()
          }}
        />
      )}

      {insertingModule && (
        <ModuleProposalModal
          title="Insert a module"
          roadmapId={id}
          draft={proposeNewModule}
          accept={(roadmapId, moduleTitle, scope) => insertModule(roadmapId, moduleTitle, scope, null)}
          onClose={() => setInsertingModule(false)}
          onApplied={async () => {
            setInsertingModule(false)
            await load()
          }}
        />
      )}

      {replanning && (
        <ReplanModulesModal
          roadmapId={id}
          draft={replanModules}
          accept={applyReplan}
          onClose={() => setReplanning(false)}
          onApplied={async () => {
            setReplanning(false)
            await load()
          }}
        />
      )}

      {reTierProposal && (
        <Modal
          onClose={cancelReTierProposal}
          title={reTierProposal.kind === 'regroup' ? `Group into modules — re-tier to ${reTierTarget}`
            : `Proposed module order — re-tier to ${reTierTarget}`}
        >
          <p className="roadmap-lead">
            {reTierProposal.kind === 'regroup'
              ? 'Nothing changes until you confirm. Every step still exists — just reorganized.'
              : 'Nothing changes until you confirm. Same modules, proposed order only.'}
          </p>
          <ul className="retier-groups">
            {reTierProposal.groups.map((g, i) => (
              <li key={i} className="retier-group">
                {reTierProposal.kind === 'regroup' ? (
                  <>
                    <strong>{g.title}</strong>
                    {g.scope && <span className="retier-group-scope"> — {g.scope}</span>}
                    <span className="retier-group-count"> ({g.entryIds.length} step{g.entryIds.length === 1 ? '' : 's'})</span>
                  </>
                ) : (
                  <span>{i + 1}. Module #{g.entryIds[0]} {g.phaseLabel && `— ${g.phaseLabel}`}</span>
                )}
              </li>
            ))}
          </ul>
          {error && <p className="roadmap-error">{error}</p>}
          <div className="roadmap-actions">
            <Button variant="ghost" onClick={cancelReTierProposal} disabled={reTiering}>
              Cancel
            </Button>
            <Button variant="primary" onClick={confirmReTierProposal} disabled={reTiering}>
              {reTiering ? 'Applying…' : 'Confirm'}
            </Button>
          </div>
        </Modal>
      )}

      {suggestingAddition && (
        <Modal onClose={closeAdditionPrompt} title={`Suggest an addition — "${canonicalTopic?.canonicalName}"`}>
          {!additionProposal ? (
            <>
              <p className="roadmap-lead">
                A subtopic, prerequisite, or alias this topic should now know about.
              </p>
              <TextArea
                value={additionSuggestion}
                onChange={(e) => setAdditionSuggestion(e.target.value)}
                rows={2}
                placeholder="e.g. Kubernetes networking"
                autoFocus
              />
              {error && <p className="roadmap-error">{error}</p>}
              <div className="roadmap-actions">
                <Button variant="ghost" onClick={closeAdditionPrompt} disabled={additionBusy}>
                  Cancel
                </Button>
                <Button variant="primary" onClick={draftAddition} disabled={additionBusy || !additionSuggestion.trim()}>
                  {additionBusy ? 'Drafting…' : 'Draft it'}
                </Button>
              </div>
            </>
          ) : (
            <>
              {additionProposal.isDuplicate && (
                <p className="roadmap-error">Looks like this may already be covered.</p>
              )}
              {!additionProposal.isRelevant && (
                <p className="roadmap-error">This may not actually belong to this topic.</p>
              )}
              <p className="roadmap-lead">
                Add “{additionProposal.value}” to {additionProposal.field}?
              </p>
              <p className="gen-interpretation">{additionProposal.reasoning}</p>
              {error && <p className="roadmap-error">{error}</p>}
              <div className="roadmap-actions">
                <Button variant="ghost" onClick={() => setAdditionProposal(null)} disabled={additionBusy}>
                  Back
                </Button>
                <Button variant="primary" onClick={confirmAddition} disabled={additionBusy}>
                  {additionBusy ? 'Adding…' : 'Confirm'}
                </Button>
              </div>
            </>
          )}
        </Modal>
      )}

      {breakDownStep && (
        <Modal onClose={() => setBreakDownStep(null)} title={`Break down "${nodeText(breakDownStep)}"`} size="lg">
          <p className="roadmap-lead">These substeps replace the step above. Edit before confirming.</p>
          <StepProposalEditor steps={breakDownSteps} onChange={setBreakDownSteps} />
          {error && <p className="roadmap-error">{error}</p>}
          <div className="roadmap-actions">
            <Button variant="ghost" onClick={() => setBreakDownStep(null)} disabled={breakDownBusy}>
              Cancel
            </Button>
            <Button variant="primary" onClick={confirmBreakDown} disabled={breakDownBusy}>
              {breakDownBusy ? 'Applying…' : 'Confirm'}
            </Button>
          </div>
        </Modal>
      )}

      {careerReflection && (
        <Modal onClose={() => setCareerReflection(null)}>
          <p className="gen-task-ack">{careerReflection}</p>
          <div className="roadmap-actions">
            <Button variant="primary" onClick={() => setCareerReflection(null)}>
              Done
            </Button>
          </div>
        </Modal>
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

// Ancestor chain from the top level down to (excluding) the target node, in order — feeds the
// deep view's breadcrumb (Phase 21). Null if the target isn't in the tree.
function findNodePath(nodes, targetId, trail = []) {
  for (const n of nodes) {
    if (n.id === targetId) return trail
    if (n.children) {
      const found = findNodePath(n.children, targetId, [...trail, n])
      if (found) return found
    }
  }
  return null
}

// Nesting depth of a node (0 = top-level, matching NodeRenderer's depth prop) — used to disable
// "break it down" once a step is already at the substep nesting cap (Phase 20).
function findNodeDepth(nodes, targetId, depth = 0) {
  for (const n of nodes) {
    if (n.id === targetId) return depth
    if (n.children) {
      const found = findNodeDepth(n.children, targetId, depth + 1)
      if (found != null) return found
    }
  }
  return null
}

// Matches RoadmapService's server-side MAX_STEP_DEPTH=3 (root roadmap not counted, so a
// frontend depth of 2 is already the deepest substep the backend will accept splitting further).
const MAX_STEP_DEPTH = 2

function BackLink({ onBack }) {
  return (
    <button className="back-link" onClick={onBack}>
      ← Roadmaps
    </button>
  )
}
