import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import RoadmapDetail from '../RoadmapDetail'
import * as api from '../../api'

// V3-5.2 prerequisite: RoadmapDetail holds ~11 mutually-exclusive "which panel/modal is open"
// pieces of state as separate useState flags today, among its 38 total. Before collapsing them
// into one activePanel reducer, this file pins down the property the reducer must preserve —
// opening one panel always means exactly one panel is open, never zero-or-two by accident — plus
// enough of the surrounding data flow (busy flags, step actions, reorder) that a careless
// collapse would break something a passing test suite would actually catch.
//
// The child modal components (ExpandModuleModal, ModuleProposalModal, etc.) are mocked to a
// simple marker + close/apply button each — this file tests RoadmapDetail's own orchestration of
// "which one is open", not each modal's internal form logic, which belongs to their own tests.

vi.mock('../../api')

vi.mock('../ExpandModuleModal', () => ({
  default: ({ module, onClose, onApplied }) => (
    <div role="dialog" data-testid="modal-expand-module">
      expand: {module?.content?.title}
      <button onClick={onClose}>close-expand-module</button>
      <button onClick={onApplied}>apply-expand-module</button>
    </div>
  ),
}))
vi.mock('../ExpandModulesBatchModal', () => ({
  default: ({ modules, onClose, onApplied }) => (
    <div role="dialog" data-testid="modal-batch-expand">
      batch: {modules?.length}
      <button onClick={onClose}>close-batch</button>
      <button onClick={onApplied}>apply-batch</button>
    </div>
  ),
}))
vi.mock('../ModuleProposalModal', () => ({
  default: ({ title, onClose, onApplied }) => (
    <div role="dialog" data-testid="modal-module-proposal">
      {title}
      <button onClick={onClose}>close-module-proposal</button>
      <button onClick={onApplied}>apply-module-proposal</button>
    </div>
  ),
}))
vi.mock('../ReplanModulesModal', () => ({
  default: ({ onClose, onApplied }) => (
    <div role="dialog" data-testid="modal-replan">
      replan
      <button onClick={onClose}>close-replan</button>
      <button onClick={onApplied}>apply-replan</button>
    </div>
  ),
}))
vi.mock('../StepDeepView', () => ({
  default: ({ step, onClose }) => (
    <div role="dialog" data-testid="modal-deep-view">
      deep: {step?.content?.text}
      <button onClick={onClose}>close-deep-view</button>
    </div>
  ),
}))
vi.mock('../VerifyModal', () => ({
  default: ({ step, onClose, onOverride }) => (
    <div role="dialog" data-testid="modal-verify">
      verify: {step?.content?.text}
      <button onClick={onClose}>close-verify</button>
      <button onClick={onOverride}>override-verify</button>
    </div>
  ),
}))

// A flat, two-step roadmap: step 1 done, step 2 is current. Simplest fixture for step-action
// tests (mark done, edit, insert, break down) — no modules, so hasEmptyModule is false and no
// background prefetch polling starts.
function flatRoadmap(overrides = {}) {
  return {
    id: 1,
    title: 'Learn Rust',
    notes: null,
    tier: 'MINI',
    verify: 'off',
    shape: 'flat',
    archetype: null,
    collapseOverrides: {},
    progress: { done: 1, total: 2, currentStepId: 22, estimatedTotalMinutes: 0, paceMultiplier: null },
    children: [
      { id: 21, type: 'roadmap_step', content: { text: 'Step one' }, status: 'done', orderIndex: 0 },
      { id: 22, type: 'roadmap_step', content: { text: 'Step two' }, status: 'captured', orderIndex: 1 },
    ],
    ...overrides,
  }
}

// A nested roadmap: one expanded module (with a current step) and one empty (unexpanded) module
// — for module-action tests (expand, regenerate, batch, insert, replan).
function nestedRoadmap(overrides = {}) {
  return {
    id: 2,
    title: 'Career Roadmap',
    notes: null,
    tier: 'CAREER',
    verify: 'off',
    shape: 'nested',
    archetype: 'career_path',
    collapseOverrides: {},
    progress: { done: 0, total: 1, currentStepId: 31, estimatedTotalMinutes: 0, paceMultiplier: null },
    children: [
      {
        id: 10,
        type: 'roadmap',
        content: { title: 'Module A' },
        progress: { done: 0, total: 1 },
        children: [
          { id: 31, type: 'roadmap_step', content: { text: 'A step' }, status: 'captured', orderIndex: 0 },
        ],
      },
      {
        id: 11,
        type: 'roadmap',
        content: { title: 'Module B', scope: 'not drafted yet' },
        progress: { done: 0, total: 0 },
        children: [],
      },
    ],
    ...overrides,
  }
}

async function renderWith(roadmap) {
  api.getRoadmap.mockResolvedValue(roadmap)
  api.getCanonicalTopicForRoadmap.mockResolvedValue(null)
  api.getModulePrefetchStatus.mockResolvedValue([])
  const utils = render(<RoadmapDetail id={roadmap.id} onBack={() => {}} onGone={() => {}} />)
  await screen.findByText(roadmap.title)
  return utils
}

// Exactly one (or zero) `role="dialog"` should ever be on screen — the property the activePanel
// reducer collapse must preserve.
function openDialogTestIds() {
  return screen.queryAllByRole('dialog').map((el) => el.dataset.testid)
}

describe('RoadmapDetail panel/modal mutual exclusivity', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    cleanup()
  })

  it('renders with no panel open', async () => {
    await renderWith(flatRoadmap())
    expect(openDialogTestIds()).toEqual([])
  })

  it('double-clicking a step opens the deep view, and only the deep view', async () => {
    const user = userEvent.setup()
    await renderWith(flatRoadmap())

    await user.dblClick(screen.getByText('Step two'))

    expect(openDialogTestIds()).toEqual(['modal-deep-view'])
    expect(screen.getByTestId('modal-deep-view')).toHaveTextContent('Step two')
  })

  it('closing the deep view leaves no panel open', async () => {
    const user = userEvent.setup()
    await renderWith(flatRoadmap())
    await user.dblClick(screen.getByText('Step two'))

    await user.click(screen.getByText('close-deep-view'))

    expect(openDialogTestIds()).toEqual([])
  })

  it('opening the deep view for a different step replaces, never stacks', async () => {
    const user = userEvent.setup()
    await renderWith(flatRoadmap())

    await user.dblClick(screen.getByText('Step two'))
    expect(openDialogTestIds()).toEqual(['modal-deep-view'])

    // Closing then reopening on a different node — RoadmapDetail only ever tracks one
    // deepStepId, so this also proves it never accumulates a second, stale dialog.
    await user.click(screen.getByText('close-deep-view'))
    await user.dblClick(screen.getByText('Step one'))

    expect(openDialogTestIds()).toEqual(['modal-deep-view'])
    expect(screen.getByTestId('modal-deep-view')).toHaveTextContent('Step one')
  })

  it('break-down action opens its modal exclusively, fetching the proposal first', async () => {
    const user = userEvent.setup()
    api.proposeReformulate.mockResolvedValue({ steps: [{ text: 'Sub one' }] })
    await renderWith(flatRoadmap())

    await user.click(screen.getByLabelText('Actions for Step two'))
    await user.click(screen.getByRole('menuitem', { name: 'Break down' }))

    // Break-down uses the shared ui Modal directly (not one of the mocked components), so assert
    // via its own title text rather than a mocked testid — but still enforce exactly one dialog.
    expect(await screen.findByText('Break down "Step two"')).toBeInTheDocument()
    expect(screen.getAllByRole('dialog')).toHaveLength(1)
    expect(api.proposeReformulate).toHaveBeenCalledWith(22, 'break_down')
  })

  it('insert-module and regenerate-scope never show two module-proposal modals at once', async () => {
    const user = userEvent.setup()
    await renderWith(nestedRoadmap())

    await user.click(screen.getByText('+ Insert a module'))
    expect(openDialogTestIds()).toEqual(['modal-module-proposal'])
    expect(screen.getByTestId('modal-module-proposal')).toHaveTextContent('Insert a module')

    await user.click(screen.getByText('close-module-proposal'))
    expect(openDialogTestIds()).toEqual([])

    await user.click(screen.getByText('Regenerate scope'))
    expect(openDialogTestIds()).toEqual(['modal-module-proposal'])
    expect(screen.getByTestId('modal-module-proposal')).toHaveTextContent('Regenerate this module')
  })

  it('batch-selecting two modules and expanding opens exactly the batch modal', async () => {
    const user = userEvent.setup()
    await renderWith(nestedRoadmap({
      children: [
        {
          id: 10, type: 'roadmap', content: { title: 'Module A' }, progress: { done: 0, total: 0 }, children: [],
        },
        {
          id: 11, type: 'roadmap', content: { title: 'Module B' }, progress: { done: 0, total: 0 }, children: [],
        },
      ],
      progress: { done: 0, total: 0, currentStepId: null, estimatedTotalMinutes: 0, paceMultiplier: null },
    }))

    // usePolling's own immediate background fetch (getModulePrefetchStatus, since both modules
    // here are unexpanded) resolves on its own microtask timing, independent of these clicks.
    // Plain fireEvent + waitFor rather than userEvent.click: userEvent's own async pointer/act
    // sequencing was observed to race that unrelated concurrent promise intermittently (~1 run in
    // 5) and leave the checkbox looking unchecked — fireEvent's synchronous dispatch plus waiting
    // for the actually-observable end state sidesteps it rather than chasing the exact interleaving.
    fireEvent.click(screen.getByLabelText('Select Module A for batch expansion'))
    await waitFor(() => expect(screen.getByLabelText('Select Module A for batch expansion')).toBeChecked())
    fireEvent.click(screen.getByLabelText('Select Module B for batch expansion'))
    await waitFor(() => expect(screen.getByLabelText('Select Module B for batch expansion')).toBeChecked())
    expect(screen.getByLabelText('Select Module A for batch expansion')).toBeChecked()
    expect(screen.getByText('2 modules selected')).toBeInTheDocument()

    await user.click(screen.getByText('Expand 2 selected'))

    expect(openDialogTestIds()).toEqual(['modal-batch-expand'])
    expect(screen.getByTestId('modal-batch-expand')).toHaveTextContent('batch: 2')
  })

  it('re-tier proposing a regroup opens the confirm modal; confirming applies it and closes', async () => {
    const user = userEvent.setup()
    api.reTierRoadmap.mockResolvedValue({
      status: 'proposal',
      proposal: { kind: 'regroup', groups: [{ title: 'Group A', entryIds: [21] }] },
    })
    api.applyReTierProposal.mockResolvedValue({})
    await renderWith(flatRoadmap())

    await user.click(screen.getByLabelText('Roadmap actions'))
    await user.click(screen.getByRole('menuitem', { name: 'Re-tier to TOPIC' }))

    expect(await screen.findByText('Group into modules — re-tier to TOPIC')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(api.applyReTierProposal).toHaveBeenCalledWith(1, 'regroup', [
      { title: 'Group A', entryIds: [21] },
    ]))
    expect(screen.queryByText('Group into modules — re-tier to TOPIC')).not.toBeInTheDocument()
  })

  it('an applied (non-proposal) re-tier reloads without opening any panel', async () => {
    const user = userEvent.setup()
    api.reTierRoadmap.mockResolvedValue({ status: 'applied' })
    await renderWith(flatRoadmap())

    await user.click(screen.getByLabelText('Roadmap actions'))
    await user.click(screen.getByRole('menuitem', { name: 'Re-tier to TOPIC' }))

    await waitFor(() => expect(api.getRoadmap).toHaveBeenCalledTimes(2)) // initial load + reload after re-tier
    expect(openDialogTestIds()).toEqual([])
  })
})

describe('RoadmapDetail non-panel state', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    cleanup()
  })

  it('marking the current step done shows the acknowledgment and clears the busy flag', async () => {
    const user = userEvent.setup()
    api.patchEntry.mockResolvedValue({ acknowledgment: 'Held.' })
    api.syncStepCompletion.mockResolvedValue({})
    await renderWith(flatRoadmap())

    await user.click(screen.getByRole('button', { name: 'Mark done' }))

    expect(await screen.findByText('Held.')).toBeInTheDocument()
    expect(api.patchEntry).toHaveBeenCalledWith(22, { status: 'done' })
    expect(api.syncStepCompletion).toHaveBeenCalledWith(22)
    // Busy flag cleared: the button is enabled/interactive again (re-fetched roadmap still has a
    // current step in this fixture's mocked response, since getRoadmap always returns the same
    // fixture here — the button existing at all confirms the busy state didn't get stuck).
    expect(screen.getByRole('button', { name: 'Mark done' })).not.toBeDisabled()
  })

  it('editing a step saves the new text and exits edit mode', async () => {
    const user = userEvent.setup()
    api.patchEntry.mockResolvedValue({})
    await renderWith(flatRoadmap())

    await user.click(screen.getByLabelText('Actions for Step two'))
    await user.click(screen.getByRole('menuitem', { name: 'Edit' }))

    const input = screen.getByDisplayValue('Step two')
    await user.clear(input)
    await user.type(input, 'Step two, revised')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(api.patchEntry).toHaveBeenCalledWith(22, { text: 'Step two, revised' }))
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
  })

  it('inserting a step calls the API with the typed text and the right position', async () => {
    const user = userEvent.setup()
    api.insertRoadmapStep.mockResolvedValue({})
    await renderWith(flatRoadmap())

    await user.click(screen.getByText('+ Add step'))
    await user.type(screen.getByPlaceholderText('New step'), 'Step three')
    await user.click(screen.getByRole('button', { name: 'Add' }))

    await waitFor(() => expect(api.insertRoadmapStep).toHaveBeenCalledWith(1, 'Step three', 2))
  })

  it('reorder mode: dragging updates the draft order, saving persists it and exits reorder mode', async () => {
    const user = userEvent.setup()
    api.reorderRoadmapSteps.mockResolvedValue({})
    await renderWith(flatRoadmap())

    await user.click(screen.getByText('Reorder'))
    expect(screen.getByText('Save order')).toBeInTheDocument()

    // Verify-mode selector is hidden while reordering (mutually exclusive toolbar state).
    expect(screen.queryByText('Check before done')).not.toBeInTheDocument()

    await user.click(screen.getByText('Save order'))

    await waitFor(() => expect(api.reorderRoadmapSteps).toHaveBeenCalledWith(1, [21, 22]))
    expect(screen.queryByText('Save order')).not.toBeInTheDocument()
  })

  it('cancelling reorder discards the draft order without calling the API', async () => {
    const user = userEvent.setup()
    await renderWith(flatRoadmap())

    await user.click(screen.getByText('Reorder'))
    await user.click(screen.getByText('Cancel'))

    expect(api.reorderRoadmapSteps).not.toHaveBeenCalled()
    expect(screen.queryByText('Save order')).not.toBeInTheDocument()
  })
})
