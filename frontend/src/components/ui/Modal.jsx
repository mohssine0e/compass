import { useEffect } from 'react'

/**
 * Modal — the overlay + centered panel used by every dialog (deep view, verify, reformulate, …).
 *
 * Clicking the dimmed overlay or pressing Escape calls `onClose`; clicks inside the panel don't
 * bubble out. Renders a × close button, and a heading when `title` is given. Put the dialog body
 * (and its own action row) in `children`.
 *
 * Positioning note: the panel scrolls within the overlay for tall content. Absolutely/fixed
 * elements a child renders (e.g. a floating toolbar) still work — they escape the panel's flow.
 *
 * @param {() => void} onClose
 * @param {string} [title]
 * @param {'md'|'lg'} [size='lg']
 * @example
 *   <Modal onClose={close} title="Your notes">…</Modal>
 */
export default function Modal({ onClose, title, size = 'lg', className = '', children }) {
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose?.()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="ui-overlay" onClick={onClose}>
      <div
        className={`ui-modal ui-modal--${size} ${className}`.trim()}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
      >
        <button className="ui-modal__close" onClick={onClose} aria-label="Close">
          ×
        </button>
        {title && <h2 className="ui-modal__title">{title}</h2>}
        {children}
      </div>
    </div>
  )
}
