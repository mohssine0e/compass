import { useEffect, useState } from 'react'
import { addModuleSteps, expandModule } from '../api'
import StepProposalEditor, { fromProposedSteps, toDraftSteps } from './StepProposalEditor'
import { Button, Modal } from './ui'

// Expand one module of a roadmap into its own steps, on demand (Phase 13). Drafts once on open
// (grounded on the module's own scope, deduped against resources already used elsewhere in the
// roadmap), then reuses the same propose→approve→apply editor as top-level generation — the
// user edits before anything is kept.
export default function ExpandModuleModal({ roadmapId, module, onClose, onApplied }) {
  const [steps, setSteps] = useState(null) // null while drafting
  const [skipped, setSkipped] = useState([])
  const [sources, setSources] = useState([])
  const [skeletonOnly, setSkeletonOnly] = useState(false)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    let alive = true
    expandModule(roadmapId, module.id)
      .then((res) => {
        if (!alive) return
        setSteps(fromProposedSteps(res.steps))
        setSkipped(res.skipped || [])
        setSources(res.sources || [])
        setSkeletonOnly(!!res.skeletonOnly)
        setLoading(false)
      })
      .catch((err) => {
        if (!alive) return
        setError(err.message)
        setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [roadmapId, module.id])

  const cleanSteps = (steps || []).filter((s) => s.text.trim())
  const canApply = cleanSteps.length > 0 && !busy

  async function apply() {
    if (!canApply) return
    setBusy(true)
    setError(null)
    try {
      await addModuleSteps(roadmapId, module.id, toDraftSteps(steps, skeletonOnly))
      onApplied()
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  return (
    <Modal onClose={onClose} title={module.content?.title} size="lg">
      {module.content?.scope && <p className="gen-lead">{module.content.scope}</p>}
      {loading && <p className="deep-faint">Working out the steps for this module…</p>}
      {error && <p className="roadmap-error">{error}</p>}
      {steps && !loading && (
        <StepProposalEditor
          steps={steps}
          onChange={setSteps}
          skipped={skipped}
          sources={sources}
          skeletonOnly={skeletonOnly}
        />
      )}
      <div className="roadmap-actions">
        <Button variant="ghost" onClick={onClose} disabled={busy}>
          Cancel
        </Button>
        {steps && !loading && (
          <Button variant="primary" onClick={apply} disabled={!canApply}>
            {busy ? 'Adding…' : 'Add these steps'}
          </Button>
        )}
      </div>
    </Modal>
  )
}
