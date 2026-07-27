import { useEffect } from 'react'

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'textarea:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

/**
 * The behavior every dialog-shaped overlay in the app needs (V3-7.1): Escape closes it, focus
 * moves into the panel on open and back to whatever had focus before on close, and the page
 * behind can't be scrolled while it's up. Shared by the `Modal` component and `StepDeepView`
 * (which predates `Modal` and grew its own markup, but a dialog is a dialog either way).
 *
 * `panelRef` must be attached to the dialog's outer panel element. Focus lands on the panel's
 * first focusable descendant, or the panel itself (give it `tabIndex={-1}`) if it has none.
 */
export function useDialogAccessibility(panelRef, onClose) {
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose?.()
    window.addEventListener('keydown', onKey)

    const previouslyFocused = document.activeElement
    const firstFocusable = panelRef.current?.querySelector(FOCUSABLE_SELECTOR)
    ;(firstFocusable || panelRef.current)?.focus()

    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.style.overflow = previousOverflow
      // The opener may itself have been removed (e.g. the row it lived on was deleted as part
      // of what this dialog just did) — restoring focus to a detached node is a silent no-op,
      // not an error, so no extra guard is needed beyond it still being focusable.
      previouslyFocused?.focus?.()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
}

/** Wire to the panel's onKeyDown so Tab/Shift+Tab cycle within it instead of escaping to the page behind. */
export function trapTabKey(panelRef, e) {
  if (e.key !== 'Tab' || !panelRef.current) return
  const focusable = panelRef.current.querySelectorAll(FOCUSABLE_SELECTOR)
  if (focusable.length === 0) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (e.shiftKey && document.activeElement === first) {
    e.preventDefault()
    last.focus()
  } else if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault()
    first.focus()
  }
}
