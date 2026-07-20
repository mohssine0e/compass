import { useEffect, useState } from 'react'
import { listRoadmaps } from '../api'
import ProgressBar from './ProgressBar'
import { Button } from './ui'
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
  const [error, setError] = useState(null)

  useEffect(() => {
    let alive = true
    listRoadmaps()
      .then((data) => alive && setRoadmaps(data))
      .catch((err) => alive && setError(err.message))
    return () => {
      alive = false
    }
  }, [])

  return (
    <div className="roadmap-list">
      <div className="roadmap-list-head">
        <h1 className="screen-title">Roadmaps</h1>
        <div className="roadmap-list-actions">
          <Button variant="ghost" onClick={onDraft}>
            Draft with AI
          </Button>
          <Button variant="primary" onClick={onNew}>
            + New roadmap
          </Button>
        </div>
      </div>

      {error && <p className="roadmap-error">{error}</p>}

      {roadmaps && roadmaps.length === 0 && (
        <p className="roadmap-empty">
          Nothing here yet. Start one when you know where you're headed.
        </p>
      )}

      {roadmaps && roadmaps.length > 0 && (
        <ul className="roadmap-cards">
          {roadmaps.map((r) => {
            const current = r.steps.find(
              (s) => s.orderIndex === r.progress.currentOrderIndex
            )
            return (
              <li key={r.id}>
                <button className="roadmap-card" onClick={() => onOpen(r.id)}>
                  <div className="roadmap-card-top">
                    <span className="roadmap-card-title">{r.title}</span>
                    <span className="roadmap-card-count">
                      {r.progress.done}/{r.progress.total}
                    </span>
                  </div>
                  <ProgressBar done={r.progress.done} total={r.progress.total} />
                  <span className="roadmap-card-current">
                    {r.progress.currentOrderIndex === null
                      ? 'All steps done.'
                      : current
                        ? `Now: ${current.content.text}`
                        : ''}
                  </span>
                  {r.progress.currentOrderIndex !== null && staleness(r.updatedAt) && (
                    <span className="roadmap-card-stale">{staleness(r.updatedAt)}</span>
                  )}
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
