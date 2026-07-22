import { useCallback, useRef, useState } from 'react'

// The significance caption shows only for the first few visits (Phase 22) — enough to learn
// what big/small means, then it gets out of the way. Tooltips stay for later.
const SIG_HINT_KEY = 'compass.sigHintSeen'
const SIG_HINT_MAX_VIEWS = 5

function shouldShowSigHint() {
  try {
    const seen = Number(localStorage.getItem(SIG_HINT_KEY) || 0)
    if (seen >= SIG_HINT_MAX_VIEWS) return false
    localStorage.setItem(SIG_HINT_KEY, String(seen + 1))
    return true
  } catch {
    return false
  }
}
import { createEntry } from '../api'
import { useSpeechRecognition } from '../hooks/useSpeechRecognition'
import './CaptureScreen.css'

export default function CaptureScreen() {
  const [text, setText] = useState('')
  const [significance, setSignificance] = useState(null) // 'big' | 'small' | null
  const [saving, setSaving] = useState(false)
  const [status, setStatus] = useState(null) // { kind: 'held' | 'error', message }
  const [showSigHint] = useState(shouldShowSigHint)
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
      const entry = await createEntry({ text: text.trim(), significance })
      setText('')
      setSignificance(null)
      // Use the self-talk-voice line when present; fall back to a plain confirmation.
      setStatus({ kind: 'held', message: entry.acknowledgment || 'Held.' })
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

      <div className="capture-significance-block">
        {showSigHint && (
          <p className="capture-sig-hint">big things come back later — small ones just get held</p>
        )}
        <div className="capture-significance" role="group" aria-label="How big is this?">
          {['big', 'small'].map((level) => (
            <button
              key={level}
              type="button"
              className={
                'sig-tap' + (significance === level ? ' is-selected' : '')
              }
              aria-pressed={significance === level}
              title={
                level === 'big'
                  ? 'Worth returning to — this will resurface later'
                  : 'A passing note — held, not pushed'
              }
              onClick={() =>
                setSignificance((cur) => (cur === level ? null : level))
              }
            >
              {level}
            </button>
          ))}
        </div>
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

        <button
          className={'capture-submit' + (canSubmit ? ' is-ready' : '')}
          onClick={submit}
          disabled={!canSubmit}
        >
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
