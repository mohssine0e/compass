import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Web Speech API dictation (see CLAUDE.md Section 3 — browser-native, no custom
 * transcription pipeline for v1). Final phrases are handed to `onFinalText` so the
 * caller can append them; interim words are exposed live for a responsive feel.
 */
export function useSpeechRecognition({ onFinalText } = {}) {
  const SpeechRecognition =
    typeof window !== 'undefined' &&
    (window.SpeechRecognition || window.webkitSpeechRecognition)
  const supported = Boolean(SpeechRecognition)

  const [listening, setListening] = useState(false)
  const [interim, setInterim] = useState('')

  const recognitionRef = useRef(null)
  const onFinalRef = useRef(onFinalText)
  onFinalRef.current = onFinalText

  useEffect(() => {
    if (!supported) return undefined

    const recognition = new SpeechRecognition()
    recognition.continuous = true
    recognition.interimResults = true
    recognition.lang = navigator.language || 'en-US'

    recognition.onresult = (event) => {
      let interimText = ''
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i]
        const transcript = result[0].transcript
        if (result.isFinal) {
          onFinalRef.current?.(transcript)
        } else {
          interimText += transcript
        }
      }
      setInterim(interimText)
    }

    recognition.onend = () => {
      setListening(false)
      setInterim('')
    }
    recognition.onerror = () => {
      setListening(false)
      setInterim('')
    }

    recognitionRef.current = recognition
    return () => {
      recognition.onresult = null
      recognition.onend = null
      recognition.onerror = null
      try {
        recognition.stop()
      } catch {
        // already stopped
      }
      recognitionRef.current = null
    }
  }, [supported, SpeechRecognition])

  const start = useCallback(() => {
    if (!recognitionRef.current || listening) return
    try {
      recognitionRef.current.start()
      setListening(true)
    } catch {
      // start() throws if already started; ignore
    }
  }, [listening])

  const stop = useCallback(() => {
    if (!recognitionRef.current) return
    try {
      recognitionRef.current.stop()
    } catch {
      // already stopped
    }
    setListening(false)
  }, [])

  const toggle = useCallback(() => {
    if (listening) stop()
    else start()
  }, [listening, start, stop])

  return { supported, listening, interim, start, stop, toggle }
}
