import { useEffect, useState } from 'react'
import { Button, Modal } from './ui'
import './GenerateRoadmapScreen.css'

/**
 * A single proposed module (title + scope) the user edits before accepting (Phase 18) — the
 * propose→approve→apply pattern shared by "regenerate this module" and "insert a module here".
 * `draft(roadmapId)` fetches the AI's proposal on open; `accept(roadmapId, title, scope)` applies
 * the (possibly edited) result.
 */
export default function ModuleProposalModal({ title: modalTitle, roadmapId, draft, accept, onClose, onApplied }) {
  const [moduleTitle, setModuleTitle] = useState('')
  const [scope, setScope] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    let alive = true
    draft(roadmapId)
      .then((res) => {
        if (!alive) return
        setModuleTitle(res.title || '')
        setScope(res.scope || '')
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roadmapId])

  const canApply = moduleTitle.trim().length > 0 && !busy

  async function apply() {
    if (!canApply) return
    setBusy(true)
    setError(null)
    try {
      await accept(roadmapId, moduleTitle.trim(), scope.trim() || null)
      onApplied()
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  return (
    <Modal onClose={onClose} title={modalTitle} size="md">
      {loading && <p className="deep-faint">Working it out…</p>}
      {error && <p className="roadmap-error">{error}</p>}
      {!loading && (
        <>
          <input
            className="step-input"
            value={moduleTitle}
            onChange={(e) => setModuleTitle(e.target.value)}
            placeholder="Module title"
            autoFocus
          />
          <input
            className="step-input gen-module-scope"
            value={scope}
            onChange={(e) => setScope(e.target.value)}
            placeholder="What falls under this module (optional)"
          />
        </>
      )}
      <div className="roadmap-actions">
        <Button variant="ghost" onClick={onClose} disabled={busy}>
          Cancel
        </Button>
        {!loading && (
          <Button variant="primary" onClick={apply} disabled={!canApply}>
            {busy ? 'Saving…' : 'Keep it'}
          </Button>
        )}
      </div>
    </Modal>
  )
}
