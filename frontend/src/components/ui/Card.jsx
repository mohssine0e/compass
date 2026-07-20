/**
 * Card — a bordered surface container (roadmap cards, focus cards, list rows).
 *
 * Render as a `div` (default) for a static panel, or `as="button"` for a clickable card (adds a
 * hover state) — used for roadmap/focus cards that open something. Extra props (onClick, etc.)
 * pass through.
 *
 * @param {'div'|'button'} [as='div']
 * @param {boolean} [interactive]  force the hover affordance (implied when as="button")
 * @example <Card as="button" onClick={() => open(id)}>…</Card>
 */
export default function Card({ as = 'div', interactive, className = '', children, ...rest }) {
  const Tag = as
  const isInteractive = interactive ?? as === 'button'
  return (
    <Tag
      className={`ui-card${isInteractive ? ' ui-card--interactive' : ''} ${className}`.trim()}
      {...rest}
    >
      {children}
    </Tag>
  )
}
