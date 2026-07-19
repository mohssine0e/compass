import { useEffect, useRef, useState } from 'react'
import { extractResume, getProfile, interpretSelfDescription, saveProfile } from '../api'
import './ProfileScreen.css'

// The learner profile (Phase 6): what you already know, so generation later doesn't re-teach
// it. Everything here is reviewed and owned by you — saving is what confirms it. Grows across
// Phase 6 tasks (skills now; resume + self-description added next).
const CONFIDENCE = [
  { value: '', label: '—' },
  { value: 'just_started', label: 'just started' },
  { value: 'comfortable', label: 'comfortable' },
  { value: 'solid', label: 'solid' },
]

// Resource formats a generated roadmap can suggest; you can mark ones to avoid (Phase 7.5).
const FORMATS = [
  { value: 'written', label: 'written' },
  { value: 'video', label: 'video' },
  { value: 'interactive', label: 'interactive' },
  { value: 'repo', label: 'code repo' },
  { value: 'book_chapter', label: 'book' },
]

export default function ProfileScreen() {
  const [skills, setSkills] = useState([])
  // { experience, education } pulled from a resume; resume skills are merged into the list above.
  const [resumeExtracted, setResumeExtracted] = useState(null)
  const [uploading, setUploading] = useState(false)
  const fileRef = useRef(null)
  const [descRaw, setDescRaw] = useState('')
  const [descTraits, setDescTraits] = useState([])
  const [avoidFormats, setAvoidFormats] = useState([])
  const [interpreting, setInterpreting] = useState(false)
  const [confirmedAt, setConfirmedAt] = useState(null)
  const [newSkill, setNewSkill] = useState('')
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    let alive = true
    getProfile()
      .then((p) => {
        if (!alive) return
        setSkills(p.skills || [])
        setResumeExtracted(p.resumeExtracted || null)
        setDescRaw((p.selfDescription && p.selfDescription.raw) || '')
        setDescTraits((p.selfDescription && p.selfDescription.traits) || [])
        setAvoidFormats((p.formatPreferences && p.formatPreferences.avoid) || [])
        setConfirmedAt(p.confirmedAt || null)
      })
      .catch((err) => alive && setError(err.message))
    return () => {
      alive = false
    }
  }, [])

  async function uploadResume(file) {
    if (!file || uploading) return
    setUploading(true)
    setError(null)
    try {
      const res = await extractResume(file)
      // Resume skills join the editable skills list (you set confidence); the rest is kept as-is.
      const existing = new Set(skills.map((s) => s.name.toLowerCase()))
      const added = (res.skills || [])
        .filter((name) => name && !existing.has(name.toLowerCase()))
        .map((name) => ({ name }))
      setSkills((prev) => [...prev, ...added])
      setResumeExtracted({
        experience: res.experience || [],
        education: res.education || [],
      })
      setSaved(false)
    } catch (err) {
      setError(err.message)
    } finally {
      setUploading(false)
      if (fileRef.current) fileRef.current.value = ''
    }
  }

  function removeResumeItem(kind, index) {
    setResumeExtracted((prev) => {
      if (!prev) return prev
      const next = { ...prev, [kind]: prev[kind].filter((_, i) => i !== index) }
      return next
    })
    setSaved(false)
  }

  async function interpret() {
    if (interpreting || !descRaw.trim()) return
    setInterpreting(true)
    setError(null)
    try {
      const res = await interpretSelfDescription(descRaw.trim())
      setDescTraits(res.traits || [])
      setSaved(false)
    } catch (err) {
      setError(err.message)
    } finally {
      setInterpreting(false)
    }
  }

  function removeTrait(trait) {
    setDescTraits((prev) => prev.filter((t) => t !== trait))
    setSaved(false)
  }

  function addSkill() {
    const name = newSkill.trim()
    if (!name) return
    if (skills.some((s) => s.name.toLowerCase() === name.toLowerCase())) {
      setNewSkill('')
      return
    }
    setSkills((prev) => [...prev, { name }])
    setNewSkill('')
    setSaved(false)
  }

  function removeSkill(name) {
    setSkills((prev) => prev.filter((s) => s.name !== name))
    setSaved(false)
  }

  function setConfidence(name, confidence) {
    setSkills((prev) =>
      prev.map((s) => {
        if (s.name !== name) return s
        const next = { name: s.name }
        if (confidence) next.confidence = confidence
        return next
      })
    )
    setSaved(false)
  }

  function toggleAvoidFormat(format) {
    setAvoidFormats((prev) =>
      prev.includes(format) ? prev.filter((f) => f !== format) : [...prev, format]
    )
    setSaved(false)
  }

  async function save() {
    if (saving) return
    setSaving(true)
    setError(null)
    const selfDescription = descRaw.trim()
      ? { raw: descRaw.trim(), traits: descTraits }
      : null
    const formatPreferences = avoidFormats.length ? { avoid: avoidFormats } : null
    try {
      const p = await saveProfile({ skills, resumeExtracted, selfDescription, formatPreferences })
      setConfirmedAt(p.confirmedAt || null)
      setSaved(true)
    } catch (err) {
      setError(err.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="profile">
      <h1 className="screen-title">Your profile</h1>
      <p className="profile-lead">
        What you already know, so a generated roadmap doesn&apos;t waste your time on it.
        Review everything below — saving is what confirms it.
      </p>

      <section className="profile-section">
        <h2 className="profile-section-title">Skills</h2>
        <div className="skill-add">
          <input
            className="skill-input"
            value={newSkill}
            onChange={(e) => setNewSkill(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && addSkill()}
            placeholder="Add a skill (e.g. Python, SQL, React)"
          />
          <button className="btn-ghost" onClick={addSkill} disabled={!newSkill.trim()}>
            Add
          </button>
        </div>

        {skills.length === 0 ? (
          <p className="profile-empty">No skills yet. Add what you already know.</p>
        ) : (
          <ul className="skill-list">
            {skills.map((s) => (
              <li className="skill-row" key={s.name}>
                <span className="skill-name">{s.name}</span>
                <select
                  className="skill-confidence"
                  value={s.confidence || ''}
                  onChange={(e) => setConfidence(s.name, e.target.value)}
                  aria-label={`Confidence in ${s.name}`}
                >
                  {CONFIDENCE.map((c) => (
                    <option key={c.value} value={c.value}>
                      {c.label}
                    </option>
                  ))}
                </select>
                <button
                  className="skill-remove"
                  onClick={() => removeSkill(s.name)}
                  aria-label={`Remove ${s.name}`}
                >
                  ×
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="profile-section">
        <h2 className="profile-section-title">Resume</h2>
        <p className="profile-section-hint">
          Optional. Upload a PDF or DOCX — skills join the list above, the rest stays here for
          you to trim. The file itself isn&apos;t stored.
        </p>
        <div className="resume-upload">
          <input
            ref={fileRef}
            type="file"
            accept=".pdf,.docx"
            className="resume-file"
            onChange={(e) => uploadResume(e.target.files[0])}
            disabled={uploading}
          />
          {uploading && <span className="profile-hint">Reading…</span>}
        </div>

        {resumeExtracted &&
          ['experience', 'education'].map((kind) =>
            (resumeExtracted[kind] || []).length > 0 ? (
              <div className="resume-group" key={kind}>
                <h3 className="resume-group-title">{kind}</h3>
                <ul className="resume-items">
                  {resumeExtracted[kind].map((item, i) => (
                    <li className="resume-item" key={i}>
                      <span className="resume-item-text">
                        {Object.values(item).filter(Boolean).join(' · ')}
                      </span>
                      <button
                        className="skill-remove"
                        onClick={() => removeResumeItem(kind, i)}
                        aria-label={`Remove ${kind} entry`}
                      >
                        ×
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null
          )}
      </section>

      <section className="profile-section">
        <h2 className="profile-section-title">How you like to learn</h2>
        <p className="profile-section-hint">
          In your own words. The system reads it into a few traits — you decide what stays.
        </p>
        <textarea
          className="desc-input"
          value={descRaw}
          onChange={(e) => {
            setDescRaw(e.target.value)
            setSaved(false)
          }}
          placeholder="e.g. I learn best by building something real, and lose interest reading theory with no payoff."
          rows={3}
        />
        <div className="desc-actions">
          <button className="btn-ghost" onClick={interpret} disabled={interpreting || !descRaw.trim()}>
            {interpreting ? 'Reading…' : 'Read into traits'}
          </button>
        </div>
        {descTraits.length > 0 && (
          <ul className="trait-list">
            {descTraits.map((t) => (
              <li className="trait-chip" key={t}>
                <span>{t}</span>
                <button className="trait-remove" onClick={() => removeTrait(t)} aria-label={`Remove ${t}`}>
                  ×
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="profile-section">
        <h2 className="profile-section-title">Learning formats</h2>
        <p className="profile-section-hint">
          Formats you&apos;d rather avoid — a generated roadmap won&apos;t suggest resources in these.
        </p>
        <div className="format-options">
          {FORMATS.map((f) => (
            <button
              key={f.value}
              type="button"
              className={'format-chip' + (avoidFormats.includes(f.value) ? ' is-avoided' : '')}
              onClick={() => toggleAvoidFormat(f.value)}
              aria-pressed={avoidFormats.includes(f.value)}
            >
              {avoidFormats.includes(f.value) ? 'avoid: ' : ''}
              {f.label}
            </button>
          ))}
        </div>
      </section>

      <div className="profile-actions">
        {error && <span className="profile-error">{error}</span>}
        {saved && <span className="profile-saved">Saved.</span>}
        {!saved && confirmedAt && <span className="profile-hint">Unsaved changes</span>}
        <button className="btn-primary" onClick={save} disabled={saving}>
          {saving ? 'Saving…' : 'Save profile'}
        </button>
      </div>
    </div>
  )
}
