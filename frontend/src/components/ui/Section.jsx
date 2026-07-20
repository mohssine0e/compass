/**
 * Section — a titled block with an optional hint line (Profile sections, deep-view sections).
 *
 * Draws the top divider + uppercase title used consistently across the app. Pass a `hint` for the
 * small explanatory line under the title. Children are the section body.
 *
 * @param {string} title
 * @param {string} [hint]
 * @example
 *   <Section title="Skills" hint="What you already know.">…</Section>
 */
export default function Section({ title, hint, className = '', children }) {
  return (
    <section className={`ui-section ${className}`.trim()}>
      {title && <h3 className="ui-section__title">{title}</h3>}
      {hint && <p className="ui-section__hint">{hint}</p>}
      {children}
    </section>
  )
}
