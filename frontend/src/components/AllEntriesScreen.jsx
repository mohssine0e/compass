import { useCallback, useEffect, useState } from 'react'
import { listEntries, patchEntry } from '../api'
import './AllEntries.css'

// Plain visibility over everything captured — no charts, no analytics (TASKS.md Phase 1).
// Ideas carry an editable progress state and their follow-through tasks (Phase 9).
const IDEA_STATES = ['captured', 'developing', 'in_motion', 'done', 'dropped']

export default function AllEntriesScreen({ onOpenRoadmap }) {
  const [entries, setEntries] = useState(null)
  const [error, setError] = useState(null)

  const load = useCallback(async () => {
    try {
      setEntries(await listEntries())
    } catch (err) {
      setError(err.message)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function setIdeaStatus(id, status) {
    setError(null)
    try {
      await patchEntry(id, { status })
      await load()
    } catch (err) {
      setError(err.message)
    }
  }

  async function toggleTask(task) {
    setError(null)
    try {
      await patchEntry(task.id, { status: task.status === 'done' ? 'captured' : 'done' })
      await load()
    } catch (err) {
      setError(err.message)
    }
  }

  const all = entries || []
  const topLevel = all.filter((e) => e.parentId == null)
  const tasksByParent = new Map()
  for (const e of all) {
    if (e.type === 'task' && e.parentId != null) {
      if (!tasksByParent.has(e.parentId)) tasksByParent.set(e.parentId, [])
      tasksByParent.get(e.parentId).push(e)
    }
  }

  return (
    <div className="all-list">
      <h1 className="screen-title">Everything</h1>

      {error && <p className="all-error">{error}</p>}
      {entries && topLevel.length === 0 && <p className="all-empty">Nothing captured yet.</p>}

      <ul className="all-items">
        {topLevel.map((e) => {
          const isRoadmap = e.type === 'roadmap'
          const isIdea = e.type === 'idea'
          const text = isRoadmap ? e.content.title : e.content.text
          const tasks = tasksByParent.get(e.id) || []
          return (
            <li key={e.id}>
              <div className={'all-row' + (isRoadmap ? ' is-clickable' : '')}>
                <span
                  className="all-text"
                  onClick={isRoadmap ? () => onOpenRoadmap(e.id) : undefined}
                  role={isRoadmap ? 'button' : undefined}
                >
                  {text}
                </span>
                <span className="all-tags">
                  <span className="all-tag">{isRoadmap ? 'roadmap' : e.type}</span>
                  {e.significance && <span className="all-tag">{e.significance}</span>}
                  {isIdea ? (
                    <select
                      className={`all-status-select status-${e.status}`}
                      value={e.status}
                      onChange={(ev) => setIdeaStatus(e.id, ev.target.value)}
                    >
                      {IDEA_STATES.map((s) => (
                        <option key={s} value={s}>
                          {s.replace('_', ' ')}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <span className={`all-status status-${e.status}`}>
                      {e.status.replace('_', ' ')}
                    </span>
                  )}
                </span>
              </div>

              {tasks.length > 0 && (
                <ul className="all-tasks">
                  {tasks.map((t) => (
                    <li key={t.id} className={'all-task' + (t.status === 'done' ? ' is-done' : '')}>
                      <button
                        className="all-task-check"
                        onClick={() => toggleTask(t)}
                        aria-label={t.status === 'done' ? 'Reopen task' : 'Mark task done'}
                      >
                        {t.status === 'done' ? '✓' : '○'}
                      </button>
                      <span className="all-task-text">{t.content.text}</span>
                    </li>
                  ))}
                </ul>
              )}
            </li>
          )
        })}
      </ul>
    </div>
  )
}
