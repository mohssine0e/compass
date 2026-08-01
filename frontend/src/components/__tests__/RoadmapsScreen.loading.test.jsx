import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import RoadmapsScreen from '../RoadmapsScreen'
import * as api from '../../api'

vi.mock('../../api')

// V4-5.1 (2026-07-30 user audit): this screen used to render nothing at all — not even a
// spinner — while the initial fetch was in flight, reading as a blank page rather than a
// screen that's working on it.
describe('RoadmapsScreen loading state', () => {
  afterEach(cleanup)

  it('shows a loading message before the initial fetch resolves', async () => {
    let resolveList
    api.listRoadmaps.mockReturnValue(new Promise((resolve) => { resolveList = resolve }))
    api.listArchivedRoadmaps.mockResolvedValue([])

    render(<RoadmapsScreen onNew={vi.fn()} onDraft={vi.fn()} onOpen={vi.fn()} />)

    expect(screen.getByText('Loading…')).toBeInTheDocument()

    resolveList([])
    await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())
    expect(screen.getByText(/Nothing here yet/)).toBeInTheDocument()
  })

  it('replaces the loading message with the roadmap list once loaded', async () => {
    api.listRoadmaps.mockResolvedValue([
      {
        id: 1,
        title: 'Learn Rust',
        shape: 'flat',
        updatedAt: new Date().toISOString(),
        progress: { done: 1, total: 3, currentStepId: 2, currentStepText: 'Step two' },
        children: [],
      },
    ])
    api.listArchivedRoadmaps.mockResolvedValue([])

    render(<RoadmapsScreen onNew={vi.fn()} onDraft={vi.fn()} onOpen={vi.fn()} />)

    await waitFor(() => expect(screen.getByText('Learn Rust')).toBeInTheDocument())
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument()
  })
})
