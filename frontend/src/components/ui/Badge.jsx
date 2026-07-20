/**
 * Badge — a small pill label (kind/weight/format tags, statuses, counts).
 *
 * `tone` sets the accent: `default` (faint outline), `brass` (highlight — e.g. a "project" step
 * or an active state), `danger` (warning/error). Non-interactive by design; for clickable pills
 * use a Button or Card.
 *
 * @param {'default'|'brass'|'danger'} [tone='default']
 * @example <Badge tone="brass">project</Badge>
 */
export default function Badge({ tone = 'default', className = '', children, ...rest }) {
  const toneClass = tone === 'default' ? '' : ` ui-badge--${tone}`
  return (
    <span className={`ui-badge${toneClass} ${className}`.trim()} {...rest}>
      {children}
    </span>
  )
}
