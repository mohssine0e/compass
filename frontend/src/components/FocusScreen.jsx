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
        // The first roadmap that's actually mid-flight (has a current step). Roadmaps are a
        // tree (Phase 13) — the backend already resolves the current leaf, so no need to walk
        // `children` here ourselves.
        const inMotion = (roadmaps || []).find((r) => r.progress.currentStepId !== null)
        setActive(inMotion || null)
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
          <button className="focus-card" onClick={() => onOpenRoadmap(active.id)}>
            <span className="focus-card-title">{active.title}</span>
            {active.progress.currentStepText && (
              <span className="focus-card-text">Now: {active.progress.currentStepText}</span>
            )}
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
