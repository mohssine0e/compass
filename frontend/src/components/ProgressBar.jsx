import './Roadmap.css'

/** Thin done/total bar. */
export default function ProgressBar({ done, total }) {
  const pct = total > 0 ? Math.round((done / total) * 100) : 0
  return (
    <div className="progress-track" role="presentation">
      <div className="progress-fill" style={{ width: `${pct}%` }} />
    </div>
  )
}
