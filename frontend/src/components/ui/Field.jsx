/**
 * Field — the app's text inputs, with one consistent style.
 *
 * `TextInput` is a single-line <input>; `TextArea` is a multi-line <textarea> (vertically
 * resizable). Both forward every prop (value, onChange, placeholder, onKeyDown, rows, autoFocus…)
 * to the native element, so they're drop-in replacements for the raw controls scattered today.
 *
 * @example <TextInput value={name} onChange={(e) => setName(e.target.value)} placeholder="Skill" />
 * @example <TextArea value={notes} onChange={onChange} rows={3} placeholder="Notes…" />
 */
export function TextInput({ className = '', ...rest }) {
  return <input className={`ui-input ${className}`.trim()} {...rest} />
}

export function TextArea({ className = '', ...rest }) {
  return <textarea className={`ui-textarea ${className}`.trim()} {...rest} />
}
