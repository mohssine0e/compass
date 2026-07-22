import { useState } from 'react'
import { patchEntry } from '../api'
import { Button, Chip, Modal, TextArea } from './ui'
import './IdeaDetailModal.css'

const STATES = ['captured', 'developing', 'in_motion', 'done', 'dropped']

// The deep view for a captured idea (Phase 14): status, significance, its linked follow-through
// tasks, and its resurface history — the system's own engagement with this idea over time, shown
// plainly rather than as a separate "AI interpretation" layer that doesn't exist yet. Everything
// here is a normal edit through the same patch endpoint the list view already uses.
export default function IdeaDetailModal({ idea, tasks = [], onClose, onChanged, onToggleTask }) {
  const [text, setText] = useState(idea.content.text || '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)

  const dirty = text.trim() !== (idea.content.text || '').trim()

  async function saveText() {
    if (!dirty || !text.trim() || saving) return
    setSaving(true)
    setError(null)
    try {
      await patchEntry(idea.id, { text: text.trim() })
      onChanged?.()
    } catch (err) {
      setError(err.message)
    } finally {
      setSaving(false)
    }
  }

  async function setStatus(status) {
    setError(null)
    try {
      await patchEntry(idea.id, { status })
      onChanged?.()
    } catch (err) {
      setError(err.message)
    }
  }

  async function setSignificance(significance) {
    setError(null)
    try {
      await patchEntry(idea.id, { significance })
      onChanged?.()
    } catch (err) {
      setError(err.message)
    }
  }

  const log = Array.isArray(idea.content.resurfaceLog) ? [...idea.content.resurfaceLog].reverse() : []

  return (
    <Modal onClose={onClose} size="md">
      <TextArea
        className="idea-detail-text"
        value={text}
        onChange={(e) => setText(e.target.value)}
        onBlur={saveText}
        rows={3}
      />
      {saving && <span className="deep-notes-status">Saving…</span>}

      <div className="idea-detail-group">
        <span className="idea-detail-group-label">Status</span>
        <div className="idea-detail-row">
          {STATES.map((s) => (
            <Chip key={s} toggle pressed={idea.status === s} onClick={() => setStatus(s)}>
              {s.replace('_', ' ')}
            </Chip>
          ))}
        </div>
      </div>

      <div className="idea-detail-group">
        <span className="idea-detail-group-label">Significance</span>
        <div className="idea-detail-row">
          <Chip toggle tone="brass" pressed={idea.significance === 'big'} onClick={() => setSignificance('big')}>
            big
          </Chip>
          <Chip toggle tone="brass" pressed={idea.significance === 'small'} onClick={() => setSignificance('small')}>
            small
          </Chip>
        </div>
      </div>

      {tasks.length > 0 && (
        <section className="idea-detail-section">
          <h3 className="deep-section-title">Next-step tasks</h3>
          <ul className="idea-detail-tasks">
            {tasks.map((t) => (
              <li key={t.id} className={t.status === 'done' ? 'is-done' : ''}>
                <button
                  className="all-task-check"
                  onClick={() => onToggleTask?.(t)}
                  aria-label={t.status === 'done' ? 'Reopen task' : 'Mark task done'}
                >
                  {t.status === 'done' ? '✓' : '○'}
                </button>
                <span>{t.content.text}</span>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="idea-detail-section">
        <h3 className="deep-section-title">Resurface history</h3>
        {idea.skipCount > 0 && (
          <p className="deep-faint">Skipped {idea.skipCount} time{idea.skipCount === 1 ? '' : 's'}.</p>
        )}
        {log.length === 0 ? (
          <p className="deep-faint">Not resurfaced yet.</p>
        ) : (
          <ul className="idea-detail-log">
            {log.map((entry, i) => (
              <li key={i}>
                <span className="idea-detail-log-date">{formatDate(entry.at)}</span>
                <span className="idea-detail-log-response">{entry.response.replace(/_/g, ' ')}</span>
                {entry.note && <span className="idea-detail-log-note">"{entry.note}"</span>}
              </li>
            ))}
          </ul>
        )}
      </section>

      {error && <p className="deep-error">{error}</p>}

      <div className="roadmap-actions">
        <Button variant="ghost" onClick={onClose}>
          Close
        </Button>
      </div>
    </Modal>
  )
}

function formatDate(iso) {
  try {
    return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
  } catch {
    return ''
  }
}
