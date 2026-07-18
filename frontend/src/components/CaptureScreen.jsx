import { useRef, useState } from 'react'
import { createEntry } from '../api'
import './CaptureScreen.css'

export default function CaptureScreen() {
  const [text, setText] = useState('')
  const [saving, setSaving] = useState(false)
  const [status, setStatus] = useState(null) // { kind: 'held' | 'error', message }
  const textareaRef = useRef(null)

  const canSubmit = text.trim().length > 0 && !saving

  async function submit() {
    if (!canSubmit) return
    setSaving(true)
    setStatus(null)
    try {
      await createEntry({ text: text.trim() })
      setText('')
      setStatus({ kind: 'held', message: 'Held.' })
      textareaRef.current?.focus()
    } catch (err) {
      setStatus({ kind: 'error', message: err.message })
    } finally {
      setSaving(false)
    }
  }

  function onKeyDown(e) {
    // Enter submits; Shift+Enter for a newline.
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      submit()
    }
  }

  return (
    <div className="capture">
      <textarea
        ref={textareaRef}
        className="capture-input"
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          if (status) setStatus(null)
        }}
        onKeyDown={onKeyDown}
        placeholder="Capture a thought."
        autoFocus
        rows={3}
      />

      <div className="capture-footer">
        <span
          className={
            'capture-status' + (status?.kind === 'error' ? ' is-error' : '')
          }
        >
          {status?.message}
        </span>
        <button className="capture-submit" onClick={submit} disabled={!canSubmit}>
          {saving ? 'Holding…' : 'Capture'}
        </button>
      </div>
    </div>
  )
}
