import { useId, useRef } from 'react'
import { trapTabKey, useDialogAccessibility } from '../../hooks/useDialogAccessibility'

/**
 * Modal — the overlay + centered panel used by every dialog (deep view, verify, reformulate, …).
 *
 * Clicking the dimmed overlay or pressing Escape calls `onClose`; clicks inside the panel don't
 * bubble out. Renders a × close button, and a heading when `title` is given. Put the dialog body
 * (and its own action row) in `children`.
 *
 * Accessibility (V3-7.1): see `useDialogAccessibility` — focus moves into the panel on open and
 * back to the opener on close, Tab is trapped inside the panel while it's open, and the page
 * behind can't be scrolled.
 *
 * Positioning note: the panel scrolls within the overlay for tall content. Absolutely/fixed
 * elements a child renders (e.g. a floating toolbar) still work — they escape the panel's flow.
 *
 * @param {() => void} onClose
 * @param {string} [title]
 * @param {'md'|'lg'|'xl'} [size='lg']
 * @example
 *   <Modal onClose={close} title="Your notes">…</Modal>
 */
export default function Modal({ onClose, title, size = 'lg', className = '', children }) {
  const panelRef = useRef(null)
  // Stable for the modal's lifetime (React 19 useId) — lets screen readers name the dialog via
  // aria-labelledby. Must stay unconditional (and must not be random per render): an id that
  // changes every render breaks the reference the moment anything inside re-renders.
  const uid = useId().replace(/:/g, '')
  const titleId = title ? `modal-title-${uid}` : undefined

  useDialogAccessibility(panelRef, onClose)

  return (
    <div className="ui-overlay" onClick={onClose}>
      <div
        ref={panelRef}
        className={`ui-modal ui-modal--${size} ${className}`.trim()}
        onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => trapTabKey(panelRef, e)}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
      >
        <button className="ui-modal__close" onClick={onClose} aria-label="Close">
          ×
        </button>
        {title && (
          <h2 className="ui-modal__title" id={titleId}>
            {title}
          </h2>
        )}
        {children}
      </div>
    </div>
  )
}
