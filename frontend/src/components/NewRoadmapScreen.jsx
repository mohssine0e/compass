import { useState } from 'react'
import { createRoadmap } from '../api'
import './NewRoadmapScreen.css'

const EMPTY_STEPS = ['', '', '']

export default function NewRoadmapScreen({ onDone }) {
  const [title, setTitle] = useState('')
  const [notes, setNotes] = useState('')
  const [steps, setSteps] = useState(EMPTY_STEPS)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)
  const [created, setCreated] = useState(null) // { title, count }

  const cleanSteps = steps.map((s) => s.trim()).filter(Boolean)
  const canCreate = title.trim().length > 0 && cleanSteps.length > 0 && !saving

  function setStep(index, value) {
    setSteps((prev) => prev.map((s, i) => (i === index ? value : s)))
    if (error) setError(null)
  }

  function addStep() {
    setSteps((prev) => [...prev, ''])
  }

  function removeStep(index) {
    setSteps((prev) =>
      prev.length > 1 ? prev.filter((_, i) => i !== index) : prev
    )
  }

  async function submit() {
    if (!canCreate) return
    setSaving(true)
    setError(null)
    try {
      const roadmap = await createRoadmap({
        title: title.trim(),
        notes: notes.trim(),
        steps: cleanSteps,
      })
      setCreated({ title: roadmap.title, count: roadmap.steps.length })
    } catch (err) {
      setError(err.message)
    } finally {
      setSaving(false)
    }
  }

  function reset() {
    setTitle('')
    setNotes('')
    setSteps(EMPTY_STEPS)
    setCreated(null)
    setError(null)
  }

  if (created) {
    return (
      <div className="roadmap-form">
        <p className="roadmap-created">
          {created.title} — {created.count}{' '}
          {created.count === 1 ? 'step' : 'steps'}. You're at the start.
        </p>
        <div className="roadmap-actions">
          <button className="btn-ghost" onClick={reset}>
            Another roadmap
          </button>
          <button className="btn-primary" onClick={onDone}>
            Done
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="roadmap-form">
      <input
        className="roadmap-title"
        value={title}
        onChange={(e) => {
          setTitle(e.target.value)
          if (error) setError(null)
        }}
        placeholder="What are you working through?"
        autoFocus
      />

      <textarea
        className="roadmap-notes"
        value={notes}
        onChange={(e) => setNotes(e.target.value)}
        placeholder="Notes (optional)"
        rows={2}
      />

      <div className="roadmap-steps">
        {steps.map((step, i) => (
          <div className="step-row" key={i}>
            <span className="step-index">{i + 1}</span>
            <input
              className="step-input"
              value={step}
              onChange={(e) => setStep(i, e.target.value)}
              placeholder={`Step ${i + 1}`}
            />
            <button
              type="button"
              className="step-remove"
              onClick={() => removeStep(i)}
              aria-label={`Remove step ${i + 1}`}
              disabled={steps.length <= 1}
            >
              ×
            </button>
          </div>
        ))}
        <button type="button" className="btn-ghost step-add" onClick={addStep}>
          + Add step
        </button>
      </div>

      <div className="roadmap-actions">
        {error && <span className="roadmap-error">{error}</span>}
        <button className="btn-primary" onClick={submit} disabled={!canCreate}>
          {saving ? 'Creating…' : 'Create roadmap'}
        </button>
      </div>
    </div>
  )
}
