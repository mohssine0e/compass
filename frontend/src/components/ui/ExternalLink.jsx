/**
 * ExternalLink — the one way to render a link that leaves the app (resource URLs, sources).
 *
 * Renders `<a target="_blank" rel="noreferrer">` in the `--link` color with a trailing ↗ glyph,
 * so every external link shares the same affordance. Pass `className` for per-site sizing/layout;
 * color and the glyph stay owned here.
 *
 * @example <ExternalLink href={r.url}>{r.title}</ExternalLink>
 */
export default function ExternalLink({ href, className = '', children, ...rest }) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noreferrer"
      className={`ui-extlink ${className}`.trim()}
      {...rest}
    >
      {children}
      <span className="ui-extlink__glyph" aria-hidden="true">↗</span>
    </a>
  )
}
