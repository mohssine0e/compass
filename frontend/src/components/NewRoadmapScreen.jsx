import { useState } from 'react'
import { createRoadmap } from '../api'
import { Button } from './ui'
import './NewRoadmapScreen.css'

const EMPTY_STEPS = ['', '', '']

export default function NewRoadmapScreen({ onCreated, onCancel }) {
  const [title, setTitle] = useState('')
  const [notes, setNotes] = useState('')
  const [steps, setSteps] = useState(EMPTY_STEPS)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)

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
      onCreated?.(roadmap.id)
    } catch (err) {
      setError(err.message)
      setSaving(false)
    }
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
        <Button type="button" variant="ghost" className="step-add" onClick={addStep}>
          + Add step
        </Button>
      </div>

      <div className="roadmap-actions">
        {error && <span className="roadmap-error">{error}</span>}
        <Button variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
        <Button variant="primary" onClick={submit} disabled={!canCreate}>
          {saving ? 'Creating…' : 'Create roadmap'}
        </Button>
      </div>
    </div>
  )
}
