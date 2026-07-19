import { useCallback, useRef, useState } from 'react'
import { respondResurfacing } from '../api'
import { useSpeechRecognition } from '../hooks/useSpeechRecognition'
import './ResurfacingScreen.css'

// Shown before capture when something stalled is worth an honest look (Phase 2).
export default function ResurfacingScreen({ prompt, onDone }) {
  const { entry, question, options } = prompt
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const textareaRef = useRef(null)

  const appendSpokenText = useCallback((chunk) => {
    const clean = chunk.trim()
    if (clean) setText((prev) => (prev ? prev.trimEnd() + ' ' : '') + clean)
  }, [])
  const speech = useSpeechRecognition({ onFinalText: appendSpokenText })

  async function respond(option, freeText) {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      if (speech.listening) speech.stop()
      await respondResurfacing(entry.id, { option, text: freeText })
      onDone()
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  const entryText = entry.content.title || entry.content.text
  // Quick options are everything except "something else" (that one opens the text box).
  const quickOptions = options.filter((o) => o.value !== 'something_else')

  return (
    <div className="resurface">
      <p className="resurface-context">On your mind a while ago:</p>
      <p className="resurface-entry">{entryText}</p>

      <h1 className="resurface-question">{question}</h1>

      <div className="resurface-options">
        {quickOptions.map((o) => (
          <button
            key={o.value}
            className="resurface-option"
            disabled={busy}
            onClick={() => respond(o.value)}
          >
            {o.label}
          </button>
        ))}
      </div>

      <div className="resurface-say">
        <textarea
          ref={textareaRef}
          className="resurface-input"
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={speech.listening ? 'Listening…' : 'or say it in your own words…'}
          rows={2}
        />
        {speech.listening && speech.interim && (
          <p className="resurface-interim">{speech.interim}</p>
        )}
        <div className="resurface-say-actions">
          {speech.supported && (
            <button
              type="button"
              className={'resurface-mic' + (speech.listening ? ' is-active' : '')}
              onClick={speech.toggle}
              aria-pressed={speech.listening}
              aria-label={speech.listening ? 'Stop speaking' : 'Speak your answer'}
              title={speech.listening ? 'Stop speaking' : 'Speak your answer'}
            >
              <MicIcon />
            </button>
          )}
          <button
            className="resurface-respond"
            disabled={busy || text.trim().length === 0}
            onClick={() => respond('something_else', text.trim())}
          >
            Respond
          </button>
        </div>
      </div>

      {error && <p className="resurface-error">{error}</p>}

      <button
        className="resurface-skip"
        disabled={busy}
        onClick={() => respond('skip')}
      >
        Skip for now
      </button>
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
