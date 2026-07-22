import { useEffect, useState } from 'react'
import { addModuleSteps, expandModule } from '../api'
import StepProposalEditor, { attachIssueCids, fromProposedSteps, toDraftSteps } from './StepProposalEditor'
import { Button, Modal } from './ui'

// Expand one module of a roadmap into its own steps (Phase 13). Most modules are already being
// drafted in the background from the moment they appear as unexpanded (see
// `getModulePrefetchStatus`) — when the caller already has that result, pass it as `prefetched`
// and this skips straight to the editor instead of drafting again on open. Falls back to the
// normal on-demand call when there's nothing prefetched yet (a background draft that's still
// running, failed, or was never started). Either way, reuses the same propose→approve→apply
// editor as top-level generation — the user edits before anything is kept.
export default function ExpandModuleModal({ roadmapId, module, prefetched, onClose, onApplied }) {
  const [steps, setSteps] = useState(prefetched ? fromProposedSteps(prefetched.steps) : null)
  const [skipped, setSkipped] = useState(prefetched?.skipped || [])
  const [sources, setSources] = useState(prefetched?.sources || [])
  const [issues, setIssues] = useState(
    prefetched ? attachIssueCids(fromProposedSteps(prefetched.steps), prefetched.issues) : []
  )
  const [skeletonOnly, setSkeletonOnly] = useState(!!prefetched?.skeletonOnly)
  const [loading, setLoading] = useState(!prefetched)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    if (prefetched) return // already have a real result, no need to draft again
    let alive = true
    expandModule(roadmapId, module.id)
      .then((res) => {
        if (!alive) return
        const editorSteps = fromProposedSteps(res.steps)
        setSteps(editorSteps)
        setSkipped(res.skipped || [])
        setSources(res.sources || [])
        setIssues(attachIssueCids(editorSteps, res.issues))
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
  }, [roadmapId, module.id, prefetched])

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
          issues={issues}
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
