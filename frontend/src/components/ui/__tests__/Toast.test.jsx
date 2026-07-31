import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import ToastStack from '../Toast'

// V4-1: the backend coalesces repeated (message, tone) notifications into one entry with a
// rising count instead of a wall of near-identical toasts — this is the frontend half, making
// sure that count is actually shown rather than silently dropped.
describe('ToastStack coalesced count', () => {
  afterEach(cleanup)

  it('renders a plain message with no count suffix when count is 1', () => {
    render(
      <ToastStack
        toasts={[{ id: 1, message: 'Roadmap ready to review.', tone: 'info', count: 1 }]}
        onDismiss={vi.fn()}
      />,
    )

    expect(screen.getByText('Roadmap ready to review.')).toBeInTheDocument()
  })

  it('appends a (×N) suffix when a notification was coalesced', () => {
    render(
      <ToastStack
        toasts={[
          {
            id: 1,
            message: "Couldn't draft this module in the background — expand it yourself.",
            tone: 'danger',
            count: 4,
          },
        ]}
        onDismiss={vi.fn()}
      />,
    )

    expect(
      screen.getByText("Couldn't draft this module in the background — expand it yourself. (×4)"),
    ).toBeInTheDocument()
  })

  it('treats a missing count as 1 (no suffix) for backward compatibility', () => {
    render(
      <ToastStack toasts={[{ id: 1, message: 'Held.', tone: 'info' }]} onDismiss={vi.fn()} />,
    )

    expect(screen.getByText('Held.')).toBeInTheDocument()
  })
})
