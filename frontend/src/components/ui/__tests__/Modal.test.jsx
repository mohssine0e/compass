import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import Modal from '../Modal'

// V3-7.1: focus moves in on open and back to the opener on close, Escape closes, Tab is
// trapped inside the panel, and body scroll is locked while the overlay is up.
describe('Modal accessibility', () => {
  afterEach(() => {
    cleanup()
    document.body.style.overflow = ''
  })

  it('moves focus to the first focusable element inside on open', () => {
    render(
      <Modal onClose={() => {}} title="Test">
        <button>First</button>
        <button>Second</button>
      </Modal>,
    )

    expect(document.activeElement).toHaveAccessibleName('Close')
  })

  it('restores focus to the opener on close', async () => {
    const user = userEvent.setup()
    function Harness() {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button onClick={() => setOpen(true)}>Open</button>
          {open && (
            <Modal onClose={() => setOpen(false)}>
              <button>Inside</button>
            </Modal>
          )}
        </>
      )
    }
    render(<Harness />)

    const opener = screen.getByText('Open')
    await user.click(opener)
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(document.activeElement).toBe(opener)
  })

  it('locks body scroll while open and releases it on close', () => {
    const { unmount } = render(
      <Modal onClose={() => {}}>
        <button>Inside</button>
      </Modal>,
    )

    expect(document.body.style.overflow).toBe('hidden')
    unmount()
    expect(document.body.style.overflow).toBe('')
  })

  it('pressing Escape calls onClose', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    render(
      <Modal onClose={onClose}>
        <button>Inside</button>
      </Modal>,
    )

    await user.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('Tab wraps from the last focusable element back to the first, trapping focus inside', async () => {
    const user = userEvent.setup()
    render(
      <Modal onClose={() => {}}>
        <button>First</button>
        <button>Last</button>
      </Modal>,
    )

    const last = screen.getByText('Last')
    last.focus()
    await user.tab()

    expect(document.activeElement).toHaveAccessibleName('Close') // the modal's own close button is first
  })

  it('Shift+Tab from the first focusable element wraps to the last', async () => {
    const user = userEvent.setup()
    render(
      <Modal onClose={() => {}}>
        <button>Only</button>
      </Modal>,
    )

    // The close button is the first focusable element; Shift+Tab from it should wrap to the last
    // (which, with a single child button, is that same button).
    document.activeElement.focus()
    await user.tab({ shift: true })

    expect(document.activeElement).toHaveTextContent('Only')
  })

  it('clicking the overlay calls onClose; clicking inside the panel does not', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    const { container } = render(
      <Modal onClose={onClose}>
        <button>Inside</button>
      </Modal>,
    )

    await user.click(screen.getByText('Inside'))
    expect(onClose).not.toHaveBeenCalled()

    await user.click(container.querySelector('.ui-overlay'))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
