/**
 * Icon set — deliberately tiny (CLAUDE.md: no new frameworks/libraries for this).
 *
 * Inline SVGs on a 16×16 grid, stroked with currentColor so they take the color of the text
 * they sit next to. Two groups:
 *  - recurring actions: edit / undo / archive / delete (kebab ⋯ and drag ⠿ stay as glyphs)
 *  - hierarchy depth: module / step / substep / sub-substep, used by the roadmap tree's
 *    depth-level treatment so nesting reads at a glance
 *
 * Size via the `size` prop (px); default 16 matches --space-4.
 */

function Icon({ size = 16, children, ...rest }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.4"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...rest}
    >
      {children}
    </svg>
  )
}

/* --- recurring actions --- */

export function IconEdit(props) {
  return (
    <Icon {...props}>
      <path d="M11.3 2.7l2 2L5.5 12.5l-2.8.8.8-2.8z" />
    </Icon>
  )
}

export function IconUndo(props) {
  return (
    <Icon {...props}>
      <path d="M3 6.5h7a3.5 3.5 0 0 1 0 7H6" />
      <path d="M6 3.5l-3 3 3 3" />
    </Icon>
  )
}

export function IconArchive(props) {
  return (
    <Icon {...props}>
      <rect x="2" y="3" width="12" height="3" rx="0.5" />
      <path d="M3 6v6.5a0.5 0.5 0 0 0 .5.5h9a0.5 0.5 0 0 0 .5-.5V6" />
      <path d="M6.5 9h3" />
    </Icon>
  )
}

export function IconDelete(props) {
  return (
    <Icon {...props}>
      <path d="M3 4.5h10" />
      <path d="M6.5 4.5V3h3v1.5" />
      <path d="M4.5 4.5l.7 8.5a0.5 0.5 0 0 0 .5.5h4.6a0.5 0.5 0 0 0 .5-.5l.7-8.5" />
    </Icon>
  )
}

/* --- hierarchy depth (module → sub-substep, boldest to faintest) --- */

export function IconModule(props) {
  return (
    <Icon {...props}>
      <rect x="2.5" y="2.5" width="11" height="11" rx="2" />
      <path d="M2.5 6.5h11" />
    </Icon>
  )
}

export function IconStep(props) {
  return (
    <Icon {...props}>
      <circle cx="8" cy="8" r="4.5" />
    </Icon>
  )
}

export function IconSubstep(props) {
  return (
    <Icon {...props}>
      <circle cx="8" cy="8" r="3" />
    </Icon>
  )
}

export function IconSubSubstep(props) {
  return (
    <Icon {...props}>
      <circle cx="8" cy="8" r="1.6" fill="currentColor" stroke="none" />
    </Icon>
  )
}
