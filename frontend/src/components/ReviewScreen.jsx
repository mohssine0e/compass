import { useEffect, useState } from 'react'
import { getReview } from '../api'
import './ReviewScreen.css'

// Cross-thread depth (Phase 10): where things stand across everything, and any recurring pattern
// — reflected back in the self-talk voice, on demand, not pushed.
export default function ReviewScreen() {
  const [review, setReview] = useState(undefined) // undefined = loading
  const [error, setError] = useState(null)

  useEffect(() => {
    let alive = true
    getReview()
      .then((r) => alive && setReview(r))
      .catch((err) => alive && setError(err.message))
    return () => {
      alive = false
    }
  }, [])

  return (
    <div className="review">
      <h1 className="screen-title">Where things stand</h1>

      {error && <p className="review-error">{error}</p>}

      {review === undefined && !error && <p className="review-faint">Taking stock…</p>}

      {review && !review.enough && (
        <p className="review-faint">
          Not enough going on yet to be worth it. Come back once a few things are in motion.
        </p>
      )}

      {review && review.enough && (
        <>
          {review.summary ? (
            <p className="review-summary">{review.summary}</p>
          ) : (
            <p className="review-faint">Couldn&apos;t take stock right now.</p>
          )}

          {review.threads && review.threads.length > 0 && (
            <section className="review-threads">
              <span className="review-threads-label">Patterns worth noticing</span>
              <ul className="review-threads-list">
                {review.threads.map((t, i) => (
                  <li key={i}>{t}</li>
                ))}
              </ul>
            </section>
          )}
        </>
      )}
    </div>
  )
}
