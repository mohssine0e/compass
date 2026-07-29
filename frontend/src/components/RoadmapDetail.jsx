import { Fragment, useCallback, useEffect, useReducer, useState } from 'react'
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
import { usePolling } from '../hooks/usePolling'
import {
  dependencyInfo,
  findNode,
  findNodeDepth,
  findNodePath,
  formatMinutes,
  hasEmptyModule,
  nodeIndexOf,
  nodeText,
  searchMatches,
  seedCollapsed,
  sessionStats,
} from '../roadmapTree'
import { NodeRenderer } from './RoadmapTree'
import StepDeepView from './StepDeepView'
import VerifyModal from './VerifyModal'
import {
  Button,
  IconArchive,
  IconDelete,
  Menu,
  Modal,
  TextArea,
} from './ui'
import './Roadmap.css'

// RB-2.5: the re-tier escape hatch's target choices, offered minus whatever the roadmap's
// current tier already is.
const RE_TIER_OPTIONS = ['TASK', 'MINI', 'TOPIC', 'CAREER']

// V3-5.2: "which panel/modal is open" as one discriminated union instead of ~11 independent
// useState flags — see the long comment at its call site in RoadmapDetail for why. Every action
// but 'close' opens exactly the named panel, replacing whatever (if anything) was open before.
export const CLOSED_PANEL = { type: null }

export function panelReducer(panel, action) {
  switch (action.type) {
    case 'close':
      return CLOSED_PANEL
    case 'deepView':
      return { type: 'deepView', stepId: action.stepId }
    case 'verify':
      return { type: 'verify', stepId: action.stepId }
    case 'expandModule':
      return { type: 'expandModule', moduleId: action.moduleId }
    case 'batchExpand':
      return { type: 'batchExpand', modules: action.modules }
    case 'regenerateModule':
      return { type: 'regenerateModule', moduleId: action.moduleId }
    case 'insertModule':
      return { type: 'insertModule' }
    case 'replan':
      return { type: 'replan' }
    case 'retierProposal':
      return { type: 'retierProposal', target: action.target, proposal: action.proposal }
    case 'suggestAddition':
      return { type: 'suggestAddition' }
    case 'breakDown':
      return { type: 'breakDown', step: action.step }
    case 'careerReflection':
      return { type: 'careerReflection', text: action.text }
    default:
      return panel
  }
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
  // V3-5.2: the ~11 "which panel/modal is open" flags below (deep view, verify, expand-module,
  // batch-expand, regenerate-module, insert-module, replan, re-tier proposal, suggest-addition,
  // break-down, career reflection) collapse into one activePanel reducer — see panelReducer below
  // this component. Nothing in the JSX or handlers past this point had to change: the derived
  // `const`s right after the early-return guards below reconstruct each old variable name/shape
  // exactly, so every existing read site keeps working unchanged; only the ~20 write sites
  // (`setX(...)` calls) become `dispatchPanel({ type: ... })`. The real change isn't cosmetic —
  // today, nothing stops two of these firing at once (each is an independent useState, and the
  // JSX just renders whichever happen to be truthy); a discriminated union makes "exactly one
  // panel, or none" true by construction instead of by accident, matching what the JSX already
  // assumed. RoadmapDetail.panels.test.jsx pins this down.
  const [panel, dispatchPanel] = useReducer(panelReducer, CLOSED_PANEL)
  // Reorder mode (Phase 12): drag-to-reorder the top-level nodes, saved explicitly.
  const [reorderMode, setReorderMode] = useState(false)
  const [draftOrder, setDraftOrder] = useState([])
  const [savingOrder, setSavingOrder] = useState(false)
  const [dragIndex, setDragIndex] = useState(null)
  // Which container nodes are collapsed (Phase 13). Seeded from fully-done groups on first load.
  const [collapsed, setCollapsed] = useState(null)
  // In-tree search (tree view only): find a step/module by name in a large, partly-collapsed
  // roadmap without expanding everything by hand first. searchMatchIndex is which match Enter
  // last jumped to, so repeated Enters cycle rather than re-jumping to the same one.
  const [searchQuery, setSearchQuery] = useState('')
  const [searchMatchIndex, setSearchMatchIndex] = useState(0)
  // For a flat roadmap: collapse the run of completed steps above the current one (Phase 12).
  const [showCompleted, setShowCompleted] = useState(false)
  // Modules picked for a batch expansion (Phase 19) — an explicit, opt-in action; the default
  // stays expanding one module at a time. A Set of module ids, until the batch modal opens with
  // the confirmed list (see the batchExpand panel below).
  const [selectedModuleIds, setSelectedModuleIds] = useState(new Set())
  // Structural tree vs. the ordered "what's next" learning path (Phase 13).
  const [view, setView] = useState('tree')
  // Background-draft status per unexpanded module id, e.g. {status, result, error} — see
  // getModulePrefetchStatus. Every unexpanded module starts drafting server-side the moment it
  // appears, so this is usually already DONE by the time the founder opens one.
  const [prefetch, setPrefetch] = useState({})

  const [reTiering, setReTiering] = useState(false)

  // Topic evolution (RB-3.10): the canonical topic this roadmap was created as/from, if it has
  // one — null while unknown/loading, false once confirmed there isn't one.
  const [canonicalTopic, setCanonicalTopic] = useState(null)
  // The "suggest a topic addition" form's own working data — stays separate from the panel
  // reducer (unlike whether the panel is open) since it changes on every keystroke and is only
  // ever read while that one panel is open anyway.
  const [additionSuggestion, setAdditionSuggestion] = useState('')
  const [additionProposal, setAdditionProposal] = useState(null)
  const [additionBusy, setAdditionBusy] = useState(false)

  // The break-down review's own editable proposal — same reasoning as additionSuggestion above.
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
  usePolling(
    roadmap && hasEmptyModule(roadmap.children || []) ? () => getModulePrefetchStatus(roadmap.id) : null,
    2500,
    (list) => {
      const map = {}
      for (const item of list) map[item.moduleId] = item
      setPrefetch(map)
    },
    [roadmap],
  )

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
          .then((res) => res?.reflection && dispatchPanel({ type: 'careerReflection', text: res.reflection }))
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
      dispatchPanel({ type: 'verify', stepId: node.id })
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
        dispatchPanel({ type: 'retierProposal', target: tier, proposal: res.proposal })
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
      dispatchPanel({ type: 'close' })
      await load()
    } catch (err) {
      setError(err.message)
    } finally {
      setReTiering(false)
    }
  }

  function cancelReTierProposal() {
    dispatchPanel({ type: 'close' })
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
    dispatchPanel({ type: 'close' })
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
      dispatchPanel({ type: 'breakDown', step: node })
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
      dispatchPanel({ type: 'close' })
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

  // Scroll a node into view, used by both "Jump to current" and search's Enter-to-jump (opt-in,
  // never automatic — Section 8's "user-initiated, never pushy" applies just as well to scrolling
  // as it does to reformulate prompts; someone who scrolled down on purpose shouldn't get yanked
  // back). Un-collapses whatever ancestor chain is hiding the target, then scrolls once the DOM
  // has actually updated to match — a raw scrollIntoView right after setCollapsed would run
  // before React commits the newly-expanded nodes.
  const [pendingScrollToId, setPendingScrollToId] = useState(null)

  function scrollToTreeNode(nodeId) {
    const ancestors = findNodePath(children, nodeId) || []
    if (ancestors.length > 0) {
      setCollapsed((prev) => {
        const next = new Set(prev)
        for (const ancestor of ancestors) next.delete(ancestor.id)
        return next
      })
    }
    setPendingScrollToId(nodeId)
  }

  useEffect(() => {
    if (pendingScrollToId == null) return
    document.getElementById(`roadmap-node-${pendingScrollToId}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    setPendingScrollToId(null)
  }, [pendingScrollToId, collapsed])

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

  // Reconstructing each pre-reducer variable name/shape exactly, so every read site below is
  // unchanged — only the write sites (setX calls) became dispatchPanel(...).
  const deepStepId = panel.type === 'deepView' ? panel.stepId : null
  const verifyStepId = panel.type === 'verify' ? panel.stepId : null
  const expandingModuleId = panel.type === 'expandModule' ? panel.moduleId : null
  const batchExpanding = panel.type === 'batchExpand' ? panel.modules : null
  const regeneratingModuleId = panel.type === 'regenerateModule' ? panel.moduleId : null
  const insertingModule = panel.type === 'insertModule'
  const replanning = panel.type === 'replan'
  const reTierProposal = panel.type === 'retierProposal' ? panel.proposal : null
  const reTierTarget = panel.type === 'retierProposal' ? panel.target : null
  const suggestingAddition = panel.type === 'suggestAddition'
  const breakDownStep = panel.type === 'breakDown' ? panel.step : null
  const careerReflection = panel.type === 'careerReflection' ? panel.text : null

  const { title, notes, progress } = roadmap
  const children = roadmap.children || []
  // Not gamification (CLAUDE.md: no streaks) — just the honest total, rolled up from data
  // already tracked per step (StepDeepView's session log). Silent below one real session; a
  // lone "0h invested" is noise, not signal.
  const { totalMinutes: investedMinutes, sessionCount } = sessionStats(children)
  // In-tree search (tree view only): matches are found against every node's own text, wherever
  // in the tree it sits; any collapsed group standing between a match and the top level is force-
  // opened for the duration of the search (not persisted as a real collapse-state change — the
  // founder's actual collapseOverrides underneath are untouched, this is purely a view override).
  const searchMatchIds = searchMatches(children, searchQuery)
  const searchMatchIdSet = new Set(searchMatchIds)
  const searchAncestorsToOpen = new Set()
  for (const matchId of searchMatchIds) {
    for (const ancestor of findNodePath(children, matchId) || []) {
      searchAncestorsToOpen.add(ancestor.id)
    }
  }
  const visibleCollapsed = searchAncestorsToOpen.size > 0
    ? new Set([...(collapsed || [])].filter((id) => !searchAncestorsToOpen.has(id)))
    : collapsed

  function goToSearchMatch(delta) {
    if (searchMatchIds.length === 0) return
    const nextIndex = (searchMatchIndex + delta + searchMatchIds.length) % searchMatchIds.length
    setSearchMatchIndex(nextIndex)
    scrollToTreeNode(searchMatchIds[nextIndex])
  }

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

  // Everything the tree renderer (RoadmapTree.jsx) needs but doesn't own itself — bundled into
  // one object, passed down through the recursion as an explicit prop, instead of the tree's
  // row/group/module components closing over this component directly.
  const treeCtx = {
    currentId,
    nodeIndex,
    editingStepId,
    editText,
    setEditText,
    saveEdit,
    cancelEdit,
    savingEdit,
    dispatchPanel,
    busyStepId,
    requestMarkDone,
    startEdit,
    startBreakDown,
    startInsert,
    graduateStepAction,
    undoStep,
    deleteStep,
    collapsed: visibleCollapsed,
    toggleCollapsed,
    flattenStepAction,
    selectedModuleIds,
    setSelectedModuleIds,
    prefetch,
    searchMatchIds: searchMatchIdSet,
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
        {sessionCount > 0 && (
          <span className="roadmap-time-invested">
            ~{formatMinutes(investedMinutes)} invested across {sessionCount} session{sessionCount === 1 ? '' : 's'}
          </span>
        )}
      </div>

      {hasRemainingModules && progress.paceMultiplier != null && progress.paceMultiplier > 2 && (
        <p className="roadmap-pace-note">
          At your current pace, this will take roughly {Math.round(progress.paceMultiplier * 10) / 10}x
          longer than estimated.{' '}
          <button className="roadmap-pace-action" onClick={() => dispatchPanel({ type: 'replan' })}>
            Redraft the remaining modules for where you actually are?
          </button>
        </p>
      )}
      {hasRemainingModules && progress.paceMultiplier != null && progress.paceMultiplier < 0.5 && (
        <p className="roadmap-pace-note">
          At your current pace, this is going roughly {Math.round((1 / progress.paceMultiplier) * 10) / 10}x
          faster than estimated.{' '}
          <button className="roadmap-pace-action" onClick={() => dispatchPanel({ type: 'replan' })}>
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
            {view === 'tree' && (
              <span className="roadmap-search">
                <input
                  type="search"
                  placeholder="Find a step or module…"
                  value={searchQuery}
                  onChange={(e) => {
                    setSearchQuery(e.target.value)
                    // -1 so the first Enter lands on the first match (index 0), not the second.
                    setSearchMatchIndex(-1)
                  }}
                  onKeyDown={(e) => {
                    if (e.key !== 'Enter') return
                    e.preventDefault()
                    goToSearchMatch(e.shiftKey ? -1 : 1)
                  }}
                />
                {searchQuery.trim() && (
                  <span className="roadmap-search-count">
                    {searchMatchIds.length === 0
                      ? 'No matches'
                      : `${(searchMatchIndex % searchMatchIds.length) + 1} of ${searchMatchIds.length}`}
                  </span>
                )}
              </span>
            )}
            {view === 'tree' && progress.currentStepId != null && (
              <Button variant="ghost" onClick={() => scrollToTreeNode(progress.currentStepId)}>
                Jump to current
              </Button>
            )}
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
                  ? [{ label: 'Suggest a topic addition', onClick: () => dispatchPanel({ type: 'suggestAddition' }) }]
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
              dispatchPanel({ type: 'batchExpand', modules: picked })
            }}
          >
            Expand {selectedModuleIds.size} selected
          </Button>
        </div>
      )}

      {view === 'path' && !reorderMode ? (
        <LearningPathView roadmap={roadmap} onOpenStep={(stepId) => dispatchPanel({ type: 'deepView', stepId })} />
      ) : view === 'projects' && !reorderMode ? (
        <ProjectsView roadmap={roadmap} onChanged={load} onOpenStep={(stepId) => dispatchPanel({ type: 'deepView', stepId })} />
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
                <NodeRenderer node={node} depth={0} parentType="roadmap" ctx={treeCtx} />
              </Fragment>
            )
          })}
          {renderInsertInput(children.length)}
          <li className="step-insert-row">
            {hasModules ? (
              <>
                <button className="step-insert-btn" onClick={() => dispatchPanel({ type: 'insertModule' })}>
                  + Insert a module
                </button>
                {hasRemainingModules && (
                  <button className="step-insert-btn" onClick={() => dispatchPanel({ type: 'replan' })}>
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
              dispatchPanel({ type: 'deepView', stepId: seg.id })
              return
            }
            dispatchPanel({ type: 'close' })
            if (seg.id != null) {
              setCollapsed((prev) => {
                const next = new Set(prev)
                next.delete(seg.id)
                return next
              })
            }
          }}
          onClose={() => dispatchPanel({ type: 'close' })}
          onChanged={load}
        />
      )}

      {verifyStepId && findNode(children, verifyStepId) && (
        <VerifyModal
          step={toStepShape(findNode(children, verifyStepId))}
          onClose={() => dispatchPanel({ type: 'close' })}
          onChanged={load}
          onPassed={async () => {
            dispatchPanel({ type: 'close' })
            setDoneNote(null)
            await load()
          }}
          onOverride={async () => {
            const target = verifyStepId
            dispatchPanel({ type: 'close' })
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
          onClose={() => dispatchPanel({ type: 'close' })}
          onApplied={async () => {
            dispatchPanel({ type: 'close' })
            await load()
          }}
        />
      )}

      {batchExpanding && (
        <ExpandModulesBatchModal
          roadmapId={id}
          modules={batchExpanding}
          onClose={() => {
            dispatchPanel({ type: 'close' })
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
          onClose={() => dispatchPanel({ type: 'close' })}
          onApplied={async () => {
            dispatchPanel({ type: 'close' })
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
          onClose={() => dispatchPanel({ type: 'close' })}
          onApplied={async () => {
            dispatchPanel({ type: 'close' })
            await load()
          }}
        />
      )}

      {replanning && (
        <ReplanModulesModal
          roadmapId={id}
          draft={replanModules}
          accept={applyReplan}
          onClose={() => dispatchPanel({ type: 'close' })}
          onApplied={async () => {
            dispatchPanel({ type: 'close' })
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
        <Modal onClose={() => dispatchPanel({ type: 'close' })} title={`Break down "${nodeText(breakDownStep)}"`} size="lg">
          <p className="roadmap-lead">These substeps replace the step above. Edit before confirming.</p>
          <StepProposalEditor steps={breakDownSteps} onChange={setBreakDownSteps} />
          {error && <p className="roadmap-error">{error}</p>}
          <div className="roadmap-actions">
            <Button variant="ghost" onClick={() => dispatchPanel({ type: 'close' })} disabled={breakDownBusy}>
              Cancel
            </Button>
            <Button variant="primary" onClick={confirmBreakDown} disabled={breakDownBusy}>
              {breakDownBusy ? 'Applying…' : 'Confirm'}
            </Button>
          </div>
        </Modal>
      )}

      {careerReflection && (
        <Modal onClose={() => dispatchPanel({ type: 'close' })}>
          <p className="gen-task-ack">{careerReflection}</p>
          <div className="roadmap-actions">
            <Button variant="primary" onClick={() => dispatchPanel({ type: 'close' })}>
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
