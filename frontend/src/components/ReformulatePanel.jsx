import { useState } from 'react'
import { applyReformulate, proposeReformulate } from '../api'
import StepProposalEditor, { fromProposedSteps, toDraftSteps } from './StepProposalEditor'
import { Badge, Button, ExternalLink } from './ui'
import './ReformulatePanel.css'

// "This is too much" — user-initiated reformulation of a step (Phase 8.5). Offers three
// approaches, drafts one (reusing the Phase 4/7.5 engines), and applies only on approval.
// Renders inline inside the step deep view (Phase 22) — swapped in for the step's body rather
// than stacked as a second overlay. `structural` marks the options that change the tree itself;
// the resource swap is content-only and rendered lighter.
const KINDS = [
  { kind: 'break_down', label: 'Break it into smaller steps', structural: true },
  { kind: 'add_prerequisite', label: 'Something to learn first', structural: true },
  { kind: 'easier_resources', label: 'Find gentler resources' },
]

export default function ReformulatePanel({ step, atMaxDepth = false, onClose, onApplied }) {
  const [proposal, setProposal] = useState(null) // { kind, ... } | { loading: true }
  const [steps, setSteps] = useState([])
  const [prerequisite, setPrerequisite] = useState('')
  const [resources, setResources] = useState([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  async function choose(kind) {
    setError(null)
    setProposal({ loading: true })
    try {
      const p = await proposeReformulate(step.id, kind)
      setSteps(kind === 'break_down' ? fromProposedSteps(p.steps) : [])
      setPrerequisite(p.prerequisite || '')
      setResources(p.resources || [])
      setProposal(p)
    } catch (err) {
      setError(err.message)
      setProposal(null)
    }
  }

  async function apply() {
    if (busy) return
    setBusy(true)
    setError(null)
    const body = { kind: proposal.kind }
    if (proposal.kind === 'break_down') body.draftSteps = toDraftSteps(steps)
    if (proposal.kind === 'add_prerequisite') body.prerequisite = prerequisite.trim()
    if (proposal.kind === 'easier_resources') body.resources = resources
    try {
      await applyReformulate(step.id, body)
      onApplied()
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  return (
    <div className="reformulate-inline">
      <button className="reformulate-back" onClick={onClose}>
        ‹ Back to step
      </button>
      <p className="reformulate-context">Too much as it stands?</p>

        {!proposal && (
          <div className="reformulate-options">
            {KINDS.map((k) =>
              k.kind === 'break_down' && atMaxDepth ? (
                <p key={k.kind} className="reformulate-note">
                  This is already broken down as far as it goes. Try something else, or edit it
                  directly.
                </p>
              ) : (
                <button
                  key={k.kind}
                  className={'reformulate-option' + (k.structural ? ' is-structural' : ' is-light')}
                  onClick={() => choose(k.kind)}
                >
                  {k.label}
                </button>
              )
            )}
          </div>
        )}

        {proposal && proposal.loading && <p className="deep-faint">Working it out…</p>}

        {proposal && !proposal.loading && (
          <div className="reformulate-proposal">
            {proposal.note && <p className="reformulate-note">{proposal.note}</p>}

            {proposal.kind === 'break_down' && (
              <>
                <p className="reformulate-lead">Replace it with these — edit before you keep them.</p>
                <StepProposalEditor steps={steps} onChange={setSteps} />
              </>
            )}

            {proposal.kind === 'add_prerequisite' && (
              <>
                {proposal.why && <p className="reformulate-note">{proposal.why}</p>}
                <p className="reformulate-lead">Do this first — edit before you keep it.</p>
                <input
                  className="step-input"
                  value={prerequisite}
                  onChange={(e) => setPrerequisite(e.target.value)}
                />
              </>
            )}

            {proposal.kind === 'easier_resources' && (
              <>
                <p className="reformulate-lead">Gentler resources for this step.</p>
                <ul className="reformulate-resources">
                  {resources.map((r, i) => (
                    <li key={i} className="reformulate-resource">
                      <ExternalLink href={r.url}>{r.title}</ExternalLink>
                      {r.format && <Badge>{r.format}</Badge>}
                      <button
                        className="step-remove"
                        onClick={() => setResources((prev) => prev.filter((_, j) => j !== i))}
                        aria-label="Remove resource"
                      >
                        ×
                      </button>
                    </li>
                  ))}
                </ul>
              </>
            )}

            {error && <p className="deep-error">{error}</p>}
            <div className="reformulate-actions">
              <Button variant="ghost" onClick={() => setProposal(null)} disabled={busy}>
                Back
              </Button>
              <Button variant="primary" onClick={apply} disabled={busy}>
                {busy ? 'Applying…' : 'Apply'}
              </Button>
            </div>
          </div>
        )}

        {!proposal && error && <p className="deep-error">{error}</p>}
    </div>
  )
}
