# UI kit (`components/ui`)

The small, shared component library the whole app builds on. **Reuse these before hand-rolling
another button/card/modal** — the point is one consistent look and one place to change it.

```js
import { Button, Card, Badge, Chip, Section, Modal, Menu, TextInput, TextArea } from './ui'
```

Importing from `./ui` also loads `ui.css` once. All styling uses the design tokens in
`src/index.css` — no hard-coded colors or magic numbers in components:

- **Color:** `--bg`, `--surface`, `--surface-2`, `--line`, `--text`, `--muted`, `--faint`,
  `--brass`, `--brass-dim`, `--danger`, `--danger-dim`, `--radius`.
- **Spacing:** `--space-1`…`--space-8` (4 · 8 · 12 · 16 · 24 · 32 · 48px). Use for
  gap/margin/padding so vertical rhythm stays consistent.
- **Type:** `--text-xs`…`--text-2xl` (0.78 → 2rem, root 17px) with `--weight-normal` /
  `--weight-medium`. Reach for a scale step rather than a one-off `rem`.

New screens should compose from these tokens and the components below — that's what keeps the
look coherent without a per-screen redesign.

## Components

| Component | What it is | Key props |
|-----------|------------|-----------|
| `Button` | The one button | `variant`: `primary` \| `ghost` \| `danger`; forwards `onClick`, `disabled`, `type` |
| `Badge` | Small pill label (tags, statuses) | `tone`: `default` \| `brass` \| `danger` |
| `Chip` | Interactive pill | removable: `onRemove`; toggle: `toggle`, `pressed`, `tone`: `brass` \| `danger` |
| `Card` | Bordered surface container | `as`: `div` \| `button`; `interactive` for the hover affordance |
| `Section` | Titled block with optional hint | `title`, `hint` |
| `Modal` | Overlay + centered panel dialog | `onClose`, `title`, `size`: `md` \| `lg`. Closes on overlay click / Escape |
| `Menu` | Kebab (⋯) dropdown of actions | `items: {label, onClick, danger?}[]` |
| `TextInput` / `TextArea` | Styled text controls | forward all native props (`value`, `onChange`, `rows`, …) |

Every component file has a full doc comment (props + an example) at the top.

## Conventions

- **Props pass through.** Components spread extra props onto the underlying element, so
  `onClick`, `disabled`, `aria-*`, `autoFocus`, etc. just work. Add classes via `className` — it's
  appended after the component's own classes.
- **Tokens, not hex.** New styles reference the CSS variables above so light/dark and future
  re-theming stay centralized.
- **Composition over options.** Prefer composing (`Card` containing a `Badge` and a `Button`) to
  adding one-off boolean props to a component.

## Adding a screen

1. Build it from these components; only reach for a bespoke element when nothing here fits.
2. Keep screen-specific CSS in that screen's own `.css`; anything reused by 2+ screens belongs in
   the kit instead.
3. Route it from `App.jsx` (a `view` name + a `NavLink`).
