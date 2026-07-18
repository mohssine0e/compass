import { useCallback, useRef, useState } from 'react'
import { createEntry } from '../api'
import { useSpeechRecognition } from '../hooks/useSpeechRecognition'
import './CaptureScreen.css'

export default function CaptureScreen() {
  const [text, setText] = useState('')
  const [significance, setSignificance] = useState(null) // 'big' | 'small' | null
  const [saving, setSaving] = useState(false)
  const [status, setStatus] = useState(null) // { kind: 'held' | 'error', message }
  const textareaRef = useRef(null)

  const appendSpokenText = useCallback((chunk) => {
    const clean = chunk.trim()
    if (!clean) return
    setText((prev) => (prev ? prev.trimEnd() + ' ' : '') + clean)
    setStatus(null)
  }, [])

  const speech = useSpeechRecognition({ onFinalText: appendSpokenText })

  const canSubmit = text.trim().length > 0 && !saving

  async function submit() {
    if (!canSubmit) return
    if (speech.listening) speech.stop()
    setSaving(true)
    setStatus(null)
    try {
      // Ideas carry significance; it stays null unless the user marked it.
      await createEntry({ text: text.trim(), significance })
      setText('')
      setSignificance(null)
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
      <div className="capture-field">
        <textarea
          ref={textareaRef}
          className="capture-input"
          value={text}
          onChange={(e) => {
            setText(e.target.value)
            if (status) setStatus(null)
          }}
          onKeyDown={onKeyDown}
          placeholder={speech.listening ? 'Listening…' : 'Capture a thought.'}
          autoFocus
          rows={3}
        />
        {speech.listening && speech.interim && (
          <p className="capture-interim">{speech.interim}</p>
        )}
      </div>

      <div className="capture-significance" role="group" aria-label="How big is this?">
        {['big', 'small'].map((level) => (
          <button
            key={level}
            type="button"
            className={
              'sig-tap' + (significance === level ? ' is-selected' : '')
            }
            aria-pressed={significance === level}
            onClick={() =>
              setSignificance((cur) => (cur === level ? null : level))
            }
          >
            {level}
          </button>
        ))}
      </div>

      <div className="capture-footer">
        <span
          className={
            'capture-status' + (status?.kind === 'error' ? ' is-error' : '')
          }
        >
          {status?.message}
        </span>

        {speech.supported && (
          <button
            type="button"
            className={'capture-mic' + (speech.listening ? ' is-active' : '')}
            onClick={speech.toggle}
            aria-pressed={speech.listening}
            aria-label={speech.listening ? 'Stop speaking' : 'Speak instead of typing'}
            title={speech.listening ? 'Stop speaking' : 'Speak instead of typing'}
          >
            <MicIcon />
          </button>
        )}

        <button className="capture-submit" onClick={submit} disabled={!canSubmit}>
          {saving ? 'Holding…' : 'Capture'}
        </button>
      </div>
    </div>
  )
}

function MicIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <rect x="9" y="3" width="6" height="11" rx="3" />
      <path d="M5 11a7 7 0 0 0 14 0" />
      <line x1="12" y1="18" x2="12" y2="21" />
    </svg>
  )
}
