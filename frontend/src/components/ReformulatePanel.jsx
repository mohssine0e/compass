import { useState } from 'react'
import { applyReformulate, proposeReformulate } from '../api'
import StepProposalEditor, { fromProposedSteps, toDraftSteps } from './StepProposalEditor'
import { Badge, Button, Modal } from './ui'
import './ReformulatePanel.css'

// "This is too much" — user-initiated reformulation of a step (Phase 8.5). Offers three
// approaches, drafts one (reusing the Phase 4/7.5 engines), and applies only on approval.
const KINDS = [
  { kind: 'break_down', label: 'Break it into smaller steps' },
  { kind: 'add_prerequisite', label: 'Something to learn first' },
  { kind: 'easier_resources', label: 'Find gentler resources' },
]

export default function ReformulatePanel({ step, onClose, onApplied }) {
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
    <Modal onClose={onClose} size="md">
      <p className="reformulate-context">Too much as it stands?</p>
      <p className="reformulate-step">{step.content.text}</p>

        {!proposal && (
          <div className="reformulate-options">
            {KINDS.map((k) => (
              <button key={k.kind} className="reformulate-option" onClick={() => choose(k.kind)}>
                {k.label}
              </button>
            ))}
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
                      <a href={r.url} target="_blank" rel="noreferrer" className="gen-resource-title">
                        {r.title}
                      </a>
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
    </Modal>
  )
}
