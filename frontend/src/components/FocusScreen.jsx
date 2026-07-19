import { useEffect, useState } from 'react'
import { getNextResurfacing, listRoadmaps } from '../api'
import './FocusScreen.css'

// A deliberately narrow view (Phase 9): one thing worth revisiting, plus where you are on one
// active roadmap. Not a dashboard, not a replacement for the plain list — just today's focus.
export default function FocusScreen({ onOpenResurfacing, onOpenRoadmap }) {
  const [prompt, setPrompt] = useState(undefined) // undefined = loading, null = none
  const [active, setActive] = useState(undefined)
  const [error, setError] = useState(null)

  useEffect(() => {
    let alive = true
    getNextResurfacing()
      .then((p) => alive && setPrompt(p || null))
      .catch(() => alive && setPrompt(null))
    listRoadmaps()
      .then((roadmaps) => {
        if (!alive) return
        // The first roadmap that's actually mid-flight (has a current step).
        const inMotion = (roadmaps || []).find((r) => r.progress.currentOrderIndex !== null)
        if (!inMotion) return setActive(null)
        const step = inMotion.steps.find((s) => s.orderIndex === inMotion.progress.currentOrderIndex)
        setActive({ roadmap: inMotion, step })
      })
      .catch((err) => alive && setError(err.message))
    return () => {
      alive = false
    }
  }, [])

  const resurfaceText =
    prompt && prompt.entry ? prompt.entry.content.title || prompt.entry.content.text : null

  return (
    <div className="focus">
      <h1 className="screen-title">Focus</h1>

      <section className="focus-block">
        <span className="focus-label">Worth revisiting</span>
        {prompt === undefined ? (
          <p className="focus-faint">…</p>
        ) : resurfaceText ? (
          <button className="focus-card" onClick={() => onOpenResurfacing(prompt)}>
            <span className="focus-card-text">{resurfaceText}</span>
            <span className="focus-card-cue">Take a look →</span>
          </button>
        ) : (
          <p className="focus-faint">Nothing right now. Clear.</p>
        )}
      </section>

      <section className="focus-block">
        <span className="focus-label">Where you are</span>
        {active === undefined ? (
          <p className="focus-faint">…</p>
        ) : active ? (
          <button className="focus-card" onClick={() => onOpenRoadmap(active.roadmap.id)}>
            <span className="focus-card-title">{active.roadmap.title}</span>
            {active.step && <span className="focus-card-text">Now: {active.step.content.text}</span>}
            <span className="focus-card-cue">Open →</span>
          </button>
        ) : (
          <p className="focus-faint">No active roadmap.</p>
        )}
      </section>

      {error && <p className="focus-error">{error}</p>}
    </div>
  )
}
