import { useEffect, useRef, useState } from 'react'

/**
 * Menu — a kebab (⋯) button that opens a small dropdown of actions.
 *
 * Used to tuck secondary/destructive actions away so rows stay clean (e.g. a step's Edit/Delete
 * behind one ⋯ instead of a wall of buttons — Phase 12). Opens on click, closes on outside-click,
 * Escape, or after choosing an item. Mark a destructive item with `danger: true`.
 *
 * @param {{label: string, onClick: () => void, danger?: boolean}[]} items
 * @param {string} [label='Actions']  accessible label for the trigger
 * @example
 *   <Menu items={[{ label: 'Edit', onClick: edit }, { label: 'Delete', onClick: del, danger: true }]} />
 */
export default function Menu({ items = [], label = 'Actions', className = '' }) {
  const [open, setOpen] = useState(false)
  const ref = useRef(null)

  useEffect(() => {
    if (!open) return undefined
    const onDown = (e) => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false)
    }
    const onKey = (e) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      window.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <span className={`ui-menu ${className}`.trim()} ref={ref}>
      <button
        className="ui-menu__trigger"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={label}
      >
        ⋯
      </button>
      {open && (
        <span className="ui-menu__list" role="menu">
          {items.map((item) => (
            <button
              key={item.label}
              className={'ui-menu__item' + (item.danger ? ' ui-menu__item--danger' : '')}
              role="menuitem"
              onClick={() => {
                setOpen(false)
                item.onClick()
              }}
            >
              {item.label}
            </button>
          ))}
        </span>
      )}
    </span>
  )
}
