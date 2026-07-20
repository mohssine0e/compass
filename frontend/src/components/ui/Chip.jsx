/**
 * Chip — an interactive pill, distinct from the non-interactive <Badge>. Two shapes:
 *
 * - Removable (default): a solid pill with a text label and an "×" that calls `onRemove`.
 *   Use for a list of user-owned tags you can drop (profile traits, keywords).
 *     <Chip onRemove={() => drop(t)}>{t}</Chip>
 *
 * - Toggle (`toggle`): a button pill with a pressed state (`pressed`). `tone` sets the "on"
 *   accent — `brass` for a normal selection, `danger` when being on is a negative choice
 *   (e.g. "avoid this format"). Fires `onClick`.
 *     <Chip toggle tone="danger" pressed={avoided} onClick={() => flip(f)}>{label}</Chip>
 *
 * Extra `className` and DOM props (onClick, aria-*, disabled, …) pass through to the element.
 *
 * @param {boolean}                [toggle]           render the toggle (button) shape
 * @param {boolean}                [pressed]          toggle only: on/selected state
 * @param {'brass'|'danger'}       [tone='brass']     toggle only: accent when pressed
 * @param {() => void}             [onRemove]         removable only: called by the × button
 */
export default function Chip({
  toggle,
  pressed,
  tone = 'brass',
  onRemove,
  className = '',
  children,
  ...rest
}) {
  if (toggle) {
    const onClass = pressed ? ` is-on ui-chip--on-${tone}` : ''
    return (
      <button
        type="button"
        className={`ui-chip ui-chip--toggle${onClass} ${className}`.trim()}
        aria-pressed={!!pressed}
        {...rest}
      >
        {children}
      </button>
    )
  }
  return (
    <span className={`ui-chip ui-chip--removable ${className}`.trim()} {...rest}>
      <span>{children}</span>
      {onRemove && (
        <button type="button" className="ui-chip__remove" onClick={onRemove} aria-label="Remove">
          ×
        </button>
      )}
    </span>
  )
}
