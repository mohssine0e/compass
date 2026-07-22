import { useCallback, useEffect, useState } from 'react'
import { listArchivedRoadmaps, listRoadmaps, setRoadmapArchived } from '../api'
import ProgressBar from './ProgressBar'
import { Badge, Button } from './ui'
import './Roadmap.css'

// A passive drift cue (Phase 9): how long since this roadmap was last touched. Only once it's
// been a day or more — no nagging, just a quiet signal next to the plain list.
function staleness(updatedAt) {
  if (!updatedAt) return null
  const days = Math.floor((Date.now() - new Date(updatedAt).getTime()) / 86400000)
  return days >= 1 ? `${days} day${days === 1 ? '' : 's'} since touched` : null
}

export default function RoadmapsScreen({ onNew, onDraft, onOpen }) {
  const [roadmaps, setRoadmaps] = useState(null)
  const [archived, setArchived] = useState(null)
  const [showArchived, setShowArchived] = useState(false)
  const [error, setError] = useState(null)

  const loadActive = useCallback(() => {
    listRoadmaps()
      .then(setRoadmaps)
      .catch((err) => setError(err.message))
  }, [])

  const loadArchived = useCallback(() => {
    listArchivedRoadmaps()
      .then(setArchived)
      .catch((err) => setError(err.message))
  }, [])

  useEffect(() => {
    loadActive()
    loadArchived()
  }, [loadActive, loadArchived])

  async function unarchive(id) {
    setError(null)
    try {
      await setRoadmapArchived(id, false)
      loadActive()
      loadArchived()
    } catch (err) {
      setError(err.message)
    }
  }

  const archivedCount = archived ? archived.length : 0
  const list = showArchived ? archived : roadmaps

  return (
    <div className="roadmap-list">
      <div className="roadmap-list-head">
        <h1 className="screen-title">{showArchived ? 'Archived' : 'Roadmaps'}</h1>
        <div className="roadmap-list-actions">
          {showArchived ? (
            <Button variant="ghost" onClick={() => setShowArchived(false)}>
              ← Back to roadmaps
            </Button>
          ) : (
            <>
              <Button variant="ghost" onClick={onDraft}>
                Draft with AI
              </Button>
              <Button variant="primary" onClick={onNew}>
                + New roadmap
              </Button>
            </>
          )}
        </div>
      </div>

      {error && <p className="roadmap-error">{error}</p>}

      {list && list.length === 0 && (
        <p className="roadmap-empty">
          {showArchived
            ? 'Nothing archived.'
            : "Nothing here yet. Start one when you know where you're headed."}
        </p>
      )}

      {list && list.length > 0 && (
        <ul className="roadmap-cards">
          {list.map((r) => {
            const complete = r.progress.currentStepId === null
            const modules = (r.children || []).filter((c) => c.type === 'roadmap').length
            return (
              <li key={r.id} className="roadmap-card-row">
                <button className="roadmap-card" onClick={() => onOpen(r.id)}>
                  <div className="roadmap-card-top">
                    <span className="roadmap-card-title">{r.title}</span>
                    {r.shape === 'flat' && <Badge>quick</Badge>}
                    {r.shape === 'nested' && (
                      <Badge tone="brass">{modules > 0 ? `${modules} modules` : 'in depth'}</Badge>
                    )}
                    <span className="roadmap-card-count">
                      {r.progress.done}/{r.progress.total}
                    </span>
                  </div>
                  <ProgressBar done={r.progress.done} total={r.progress.total} />
                  <span className="roadmap-card-current">
                    {complete
                      ? 'All steps done.'
                      : r.progress.currentStepText
                        ? `Now: ${r.progress.currentStepText}`
                        : ''}
                  </span>
                  {!showArchived && !complete && staleness(r.updatedAt) && (
                    <span className="roadmap-card-stale">{staleness(r.updatedAt)}</span>
                  )}
                </button>
                {showArchived && (
                  <Button variant="ghost" onClick={() => unarchive(r.id)}>
                    Unarchive
                  </Button>
                )}
              </li>
            )
          })}
        </ul>
      )}

      {!showArchived && archivedCount > 0 && (
        <button className="roadmap-archived-link" onClick={() => setShowArchived(true)}>
          Archived ({archivedCount})
        </button>
      )}
    </div>
  )
}
