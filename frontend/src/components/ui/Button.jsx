/**
 * Button — the one button in the app.
 *
 * The three variants cover every case in Compass: `primary` (the brass call-to-action, e.g.
 * "Create roadmap", "Save"), `ghost` (secondary/neutral, e.g. "Cancel", "Add"), and `danger`
 * (destructive, e.g. "Delete"). Everything else (onClick, disabled, type, aria-*) passes straight
 * through to the underlying <button>.
 *
 * @param {'primary'|'ghost'|'danger'} [variant='ghost']
 * @param {string} [className]  extra classes appended after the variant class
 * @example <Button variant="primary" onClick={save} disabled={busy}>Save</Button>
 */
export default function Button({ variant = 'ghost', className = '', children, ...rest }) {
  return (
    <button className={`ui-btn ui-btn--${variant} ${className}`.trim()} {...rest}>
      {children}
    </button>
  )
}
