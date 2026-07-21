import { useEffect, useState } from 'react'
import { addModuleSteps, expandModulesBatch } from '../api'
import StepProposalEditor, { attachIssueCids, fromProposedSteps, toDraftSteps } from './StepProposalEditor'
import { Badge, Button, Modal } from './ui'

// Expand several modules at once (Phase 19), only on explicit request — the backend runs them
// concurrently (capped), but review/accept stays per-module, exactly like the single-module
// flow, just stacked so the founder doesn't have to reopen the expand action for each one.
export default function ExpandModulesBatchModal({ roadmapId, modules, onClose, onApplied }) {
  const [results, setResults] = useState(null) // null while drafting
  const [stepsByModule, setStepsByModule] = useState({})
  const [issuesByModule, setIssuesByModule] = useState({})
  const [appliedIds, setAppliedIds] = useState(new Set())
  const [busyModuleId, setBusyModuleId] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let alive = true
    expandModulesBatch(roadmapId, modules.map((m) => m.id))
      .then((res) => {
        if (!alive) return
        setResults(res)
        const nextSteps = {}
        const nextIssues = {}
        for (const r of res) {
          if (r.result) {
            const editorSteps = fromProposedSteps(r.result.steps)
            nextSteps[r.moduleId] = editorSteps
            // Computed once, right here, from the freshly-converted array — stays correct even
            // after later edits/removals shift stepsByModule's indices.
            nextIssues[r.moduleId] = attachIssueCids(editorSteps, r.result.issues)
          }
        }
        setStepsByModule(nextSteps)
        setIssuesByModule(nextIssues)
      })
      .catch((err) => {
        if (!alive) return
        setError(err.message)
      })
    return () => {
      alive = false
    }
  }, [roadmapId, modules])

  async function apply(moduleId) {
    const steps = stepsByModule[moduleId]
    const cleanSteps = (steps || []).filter((s) => s.text.trim())
    if (!cleanSteps.length) return
    setBusyModuleId(moduleId)
    setError(null)
    try {
      const result = results.find((r) => r.moduleId === moduleId)?.result
      await addModuleSteps(roadmapId, moduleId, toDraftSteps(steps, !!result?.skeletonOnly))
      setAppliedIds((prev) => new Set(prev).add(moduleId))
      onApplied()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusyModuleId(null)
    }
  }

  const allApplied = results && results.every((r) => !r.result || appliedIds.has(r.moduleId))

  return (
    <Modal onClose={onClose} title={`Expanding ${modules.length} modules`} size="lg">
      {!results && <p className="deep-faint">Drafting steps for {modules.length} modules at once…</p>}
      {error && <p className="roadmap-error">{error}</p>}
      {results && results.map((r) => {
        const module = modules.find((m) => m.id === r.moduleId)
        const applied = appliedIds.has(r.moduleId)
        return (
          <div className="gen-step-batch-module" key={r.moduleId}>
            <h3 className="gen-step-batch-title">
              {module?.content?.title || `Module ${r.moduleId}`}
              {applied && <Badge tone="brass">added</Badge>}
              {r.error && <Badge tone="danger">failed</Badge>}
            </h3>
            {r.error && <p className="roadmap-error">{r.error}</p>}
            {r.result && !applied && (
              <>
                <StepProposalEditor
                  steps={stepsByModule[r.moduleId] || []}
                  onChange={(next) => setStepsByModule((prev) => ({ ...prev, [r.moduleId]: next }))}
                  skipped={r.result.skipped || []}
                  sources={r.result.sources || []}
                  skeletonOnly={!!r.result.skeletonOnly}
                  issues={issuesByModule[r.moduleId] || []}
                />
                <Button
                  variant="primary"
                  onClick={() => apply(r.moduleId)}
                  disabled={busyModuleId === r.moduleId}
                >
                  {busyModuleId === r.moduleId ? 'Adding…' : 'Add these steps'}
                </Button>
              </>
            )}
          </div>
        )
      })}
      <div className="roadmap-actions">
        <Button variant="ghost" onClick={onClose}>
          {allApplied ? 'Done' : 'Close'}
        </Button>
      </div>
    </Modal>
  )
}
