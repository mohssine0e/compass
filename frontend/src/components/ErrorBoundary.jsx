import { Component } from 'react'
import './ErrorBoundary.css'

/**
 * Catches a render/lifecycle throw in whatever screen is mounted (V3-0.4).
 *
 * <p>Before this, a single bad render was a white screen — and on an installed PWA, with no
 * address bar and no reload affordance, that reads as the app being broken rather than one view
 * being broken. Deliberately NOT a full-page takeover: it renders inside `<main>`, so the header
 * and nav stay live and the founder can just navigate somewhere else.
 *
 * The copy follows the self-talk voice (CLAUDE.md Section 2) — a flat statement of what happened
 * and the one fact that actually matters (the data is fine), not an apology or a support prompt.
 */
export default class ErrorBoundary extends Component {
  state = { error: null }

  static getDerivedStateFromError(error) {
    return { error }
  }

  componentDidCatch(error, info) {
    // Ordinary console logging, not system_events: this is a frontend bug to debug in devtools,
    // and CLAUDE.md keeps that table for short operational signal, not stack traces.
    console.error('Screen failed to render:', error, info?.componentStack)
  }

  reset = () => {
    this.setState({ error: null })
    this.props.onReset?.()
  }

  render() {
    const { error } = this.state
    if (!error) return this.props.children

    return (
      <div className="crash" role="alert">
        <p className="crash-lead">This screen broke while rendering. Nothing was lost.</p>
        <button type="button" className="crash-action" onClick={this.reset}>
          Back to capture
        </button>
        <details className="crash-details">
          <summary>What broke</summary>
          <pre>{error.message || String(error)}</pre>
        </details>
      </div>
    )
  }
}
