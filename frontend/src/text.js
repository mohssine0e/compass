// Word-boundary truncation (Phase 21). The two-line CSS clamp alone still cuts mid-word at the
// end of its last line, so text that can run long is pre-trimmed at a word break; the clamp
// stays as a layout safety net for unusually narrow viewports. `max` should be sized to fill
// the clamp's lines at the call site's typical width without reaching the CSS cutoff.
export function truncateAtWord(text, max = 110) {
  if (typeof text !== 'string' || text.length <= max) return text
  const cut = text.lastIndexOf(' ', max)
  return `${text.slice(0, cut > max / 2 ? cut : max).trimEnd()}…`
}
