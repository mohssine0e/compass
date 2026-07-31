import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import StepProposalEditor, { newBlankStep } from '../StepProposalEditor'

// V4-3.2 (2026-07-30 user audit): "Grounded in" sources used to render as plain unclickable
// text — the backend had the real source url all along but discarded it before the DTO, so even
// wiring up an <a> here wouldn't have been enough on its own. Covers the frontend half now that
// both are fixed: a source with a url renders as a real link; one without (defensive — every
// grounded source should have a url in practice) still renders its title as plain text rather
// than crashing or showing a broken link.
describe('StepProposalEditor grounded-in sources', () => {
  afterEach(cleanup)

  function renderEditor(sources) {
    render(
      <StepProposalEditor steps={[newBlankStep()]} onChange={vi.fn()} sources={sources} />,
    )
  }

  it('renders a source with a url as a real clickable link', () => {
    renderEditor([{ title: 'Zero-knowledge proof', url: 'https://en.wikipedia.org/wiki/Zero-knowledge_proof' }])

    const link = screen.getByRole('link', { name: /Zero-knowledge proof/ })
    expect(link).toHaveAttribute('href', 'https://en.wikipedia.org/wiki/Zero-knowledge_proof')
    expect(link).toHaveAttribute('target', '_blank')
  })

  it('appends the source domain next to the title', () => {
    renderEditor([{ title: 'Zero-knowledge proof', url: 'https://en.wikipedia.org/wiki/Zero-knowledge_proof' }])

    expect(screen.getByText(/en\.wikipedia\.org/)).toBeInTheDocument()
  })

  it('falls back to plain text for a source with no url instead of a broken link', () => {
    renderEditor([{ title: 'A source with no url', url: null }])

    expect(screen.getByText('A source with no url')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /A source with no url/ })).not.toBeInTheDocument()
  })

  it('renders nothing under "Grounded in" when there are no sources', () => {
    renderEditor([])

    expect(screen.queryByText('Grounded in:')).not.toBeInTheDocument()
  })
})
