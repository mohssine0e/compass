import { useEffect, useState } from 'react'
import { Button, Modal } from './ui'
import './GenerateRoadmapScreen.css'

/**
 * "Replan remaining modules" (Phase 18): every not-yet-expanded module gets redrafted given the
 * already-expanded ones' real progress, all in one propose→approve→apply round. Already-expanded
 * or in-progress modules are left untouched — this only ever touches the ones still empty.
 */
export default function ReplanModulesModal({ roadmapId, draft, accept, onClose, onApplied }) {
  const [modules, setModules] = useState(null) // null while loading
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    let alive = true
    draft(roadmapId)
      .then((res) => {
        if (alive) setModules(res)
      })
      .catch((err) => {
        if (alive) setError(err.message)
      })
    return () => {
      alive = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roadmapId])

  function setField(moduleId, field, value) {
    setModules((prev) => prev.map((m) => (m.moduleId === moduleId ? { ...m, [field]: value } : m)))
  }

  const canApply = !!modules && modules.every((m) => m.title.trim()) && !busy

  async function apply() {
    if (!canApply) return
    setBusy(true)
    setError(null)
    try {
      await accept(roadmapId, modules)
      onApplied()
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  return (
    <Modal onClose={onClose} title="Replan remaining modules" size="lg">
      {modules === null && !error && <p className="deep-faint">Working it out…</p>}
      {error && <p className="roadmap-error">{error}</p>}
      {modules && (
        <div className="roadmap-steps">
          {modules.map((m, i) => (
            <div className="gen-step" key={m.moduleId}>
              <div className="step-row">
                <span className="step-index">{i + 1}</span>
                <input
                  className="step-input"
                  value={m.title}
                  onChange={(e) => setField(m.moduleId, 'title', e.target.value)}
                  placeholder={`Module ${i + 1}`}
                />
              </div>
              <input
                className="step-input gen-module-scope"
                value={m.scope || ''}
                onChange={(e) => setField(m.moduleId, 'scope', e.target.value)}
                placeholder="What falls under this module (optional)"
              />
            </div>
          ))}
        </div>
      )}
      <div className="roadmap-actions">
        {error && <span className="roadmap-error">{error}</span>}
        <Button variant="ghost" onClick={onClose} disabled={busy}>
          Cancel
        </Button>
        {modules && (
          <Button variant="primary" onClick={apply} disabled={!canApply}>
            {busy ? 'Saving…' : 'Keep these'}
          </Button>
        )}
      </div>
    </Modal>
  )
}
