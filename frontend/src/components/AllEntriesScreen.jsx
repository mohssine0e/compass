import { useEffect, useState } from 'react'
import { listEntries } from '../api'
import './AllEntries.css'

// Plain visibility over everything captured — no charts, no analytics (TASKS.md Phase 1).
export default function AllEntriesScreen({ onOpenRoadmap }) {
  const [entries, setEntries] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    let alive = true
    listEntries()
      .then((data) => alive && setEntries(data))
      .catch((err) => alive && setError(err.message))
    return () => {
      alive = false
    }
  }, [])

  // Top-level things only: ideas and roadmaps. Steps live inside their roadmap.
  const topLevel = (entries || []).filter((e) => e.parentId == null)

  return (
    <div className="all-list">
      <h1 className="screen-title">Everything</h1>

      {error && <p className="all-error">{error}</p>}
      {entries && topLevel.length === 0 && (
        <p className="all-empty">Nothing captured yet.</p>
      )}

      <ul className="all-items">
        {topLevel.map((e) => {
          const isRoadmap = e.type === 'roadmap'
          const text = isRoadmap ? e.content.title : e.content.text
          const Row = isRoadmap ? 'button' : 'div'
          return (
            <li key={e.id}>
              <Row
                className={'all-row' + (isRoadmap ? ' is-clickable' : '')}
                onClick={isRoadmap ? () => onOpenRoadmap(e.id) : undefined}
              >
                <span className="all-text">{text}</span>
                <span className="all-tags">
                  <span className="all-tag">{isRoadmap ? 'roadmap' : e.type}</span>
                  {e.significance && (
                    <span className="all-tag">{e.significance}</span>
                  )}
                  <span className={`all-status status-${e.status}`}>
                    {e.status.replace('_', ' ')}
                  </span>
                </span>
              </Row>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
