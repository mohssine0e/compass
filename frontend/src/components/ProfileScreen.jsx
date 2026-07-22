import { useEffect, useRef, useState } from 'react'
import {
  extractResume,
  getInference,
  getProfile,
  interpretSelfDescription,
  saveProfile,
} from '../api'
import { Button, Chip, Section } from './ui'
import './ProfileScreen.css'

// The learner profile (Phase 6): what you already know, so generation later doesn't re-teach
// it. Everything here is reviewed and owned by you — saving is what confirms it.
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

// Structured how-you-learn options (Phase 15) — selectable, not just prose, so generation can
// act on them deliberately (step sizing, resource choice) rather than guess from free text.
const LEARNING_PREFERENCE_GROUPS = [
  {
    key: 'pace',
    label: 'Pace',
    options: [
      { value: 'slow', label: 'slow and thorough' },
      { value: 'steady', label: 'steady' },
      { value: 'fast', label: 'fast, skim the basics' },
    ],
  },
  {
    key: 'theoryVsPractice',
    label: 'Theory vs. practice',
    options: [
      { value: 'theory_first', label: 'theory first' },
      { value: 'balanced', label: 'balanced' },
      { value: 'practice_first', label: 'practice first' },
    ],
  },
  {
    key: 'sessionLength',
    label: 'Session length',
    options: [
      { value: 'short', label: 'short bursts' },
      { value: 'medium', label: 'medium' },
      { value: 'long', label: 'long, deep sessions' },
    ],
  },
  {
    key: 'depth',
    label: 'Depth',
    options: [
      { value: 'overview', label: 'overview' },
      { value: 'working_knowledge', label: 'working knowledge' },
      { value: 'deep_mastery', label: 'deep mastery' },
    ],
  },
  {
    key: 'exampleVsPrinciple',
    label: 'Explanations',
    options: [
      { value: 'example_first', label: 'example first' },
      { value: 'principle_first', label: 'principle first' },
    ],
  },
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
  // Set only via confirmed behavioral inference (Phase 20) — no chips to state it directly, a
  // soft bias in resource suggestions, never a hard requirement.
  const [preferFormats, setPreferFormats] = useState([])
  const [learningPreferences, setLearningPreferences] = useState({})
  const [inferred, setInferred] = useState([])
  const [proposal, setProposal] = useState(null) // { preferences, basis } | null
  const [analyzing, setAnalyzing] = useState(false)
  const [interpreting, setInterpreting] = useState(false)
  const [confirmedAt, setConfirmedAt] = useState(null)
  const [newSkill, setNewSkill] = useState('')
  // Name of the skill whose confidence picker is open (Phase 23) — a skill with no confidence
  // renders as a compact chip until clicked, so a 39-skill import doesn't turn into a wall of
  // rows; picking a confidence promotes it to the full row permanently.
  const [pickingConfidenceFor, setPickingConfidenceFor] = useState(null)
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
        setPreferFormats((p.formatPreferences && p.formatPreferences.prefer) || [])
        setLearningPreferences(p.learningPreferences || {})
        setInferred(p.inferredPreferences || [])
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
        .map((name) => ({ name, source: 'resume' }))
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
        if (s.source) next.source = s.source
        if (confidence) next.confidence = confidence
        return next
      })
    )
    setSaved(false)
  }

  // Picking a confidence from the inline chip picker promotes the skill to a full row and
  // closes the picker in one action.
  function pickConfidence(name, confidence) {
    setConfidence(name, confidence)
    setPickingConfidenceFor(null)
  }

  function toggleAvoidFormat(format) {
    setAvoidFormats((prev) =>
      prev.includes(format) ? prev.filter((f) => f !== format) : [...prev, format]
    )
    setSaved(false)
  }

  // Single-select per group; clicking the already-selected option clears it.
  function setLearningPreference(key, value) {
    setLearningPreferences((prev) => {
      const next = { ...prev }
      if (next[key] === value) delete next[key]
      else next[key] = value
      return next
    })
    setSaved(false)
  }

  async function analyze() {
    if (analyzing) return
    setAnalyzing(true)
    setError(null)
    try {
      setProposal(await getInference())
    } catch (err) {
      setError(err.message)
    } finally {
      setAnalyzing(false)
    }
  }

  function keepPreference(pref) {
    if (!inferred.includes(pref.text)) setInferred((prev) => [...prev, pref.text])
    if (pref.avoidFormat && !avoidFormats.includes(pref.avoidFormat)) {
      setAvoidFormats((prev) => [...prev, pref.avoidFormat])
    }
    if (pref.preferFormat && !preferFormats.includes(pref.preferFormat)) {
      setPreferFormats((prev) => [...prev, pref.preferFormat])
    }
    setProposal((prev) =>
      prev ? { ...prev, preferences: prev.preferences.filter((p) => p.text !== pref.text) } : prev
    )
    setSaved(false)
  }

  function removeInferred(text) {
    setInferred((prev) => prev.filter((t) => t !== text))
    setSaved(false)
  }

  async function save() {
    if (saving) return
    setSaving(true)
    setError(null)
    const selfDescription = descRaw.trim()
      ? { raw: descRaw.trim(), traits: descTraits }
      : null
    const formatPreferences = avoidFormats.length || preferFormats.length
      ? { ...(avoidFormats.length ? { avoid: avoidFormats } : {}),
          ...(preferFormats.length ? { prefer: preferFormats } : {}) }
      : null
    try {
      const p = await saveProfile({
        skills,
        resumeExtracted,
        selfDescription,
        formatPreferences,
        inferredPreferences: inferred,
        learningPreferences,
      })
      setConfirmedAt(p.confirmedAt || null)
      setSaved(true)
    } catch (err) {
      setError(err.message)
    } finally {
      setSaving(false)
    }
  }

  const rated = skills.filter((s) => s.confidence)
  const unratedResume = skills.filter((s) => !s.confidence && s.source === 'resume')
  const unratedHand = skills.filter((s) => !s.confidence && s.source !== 'resume')

  return (
    <div className="profile">
      <h1 className="screen-title">Your profile</h1>
      <p className="profile-lead">
        What you already know, so a generated roadmap doesn&apos;t waste your time on it.
        Review everything below — saving is what confirms it.
      </p>

      <Section title="Skills">
        <div className="skill-add">
          <input
            className="skill-input"
            value={newSkill}
            onChange={(e) => setNewSkill(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && addSkill()}
            placeholder="Add a skill (e.g. Python, SQL, React)"
          />
          <Button variant="ghost" onClick={addSkill} disabled={!newSkill.trim()}>
            Add
          </Button>
        </div>

        {skills.length === 0 ? (
          <p className="profile-empty">No skills yet. Add what you already know.</p>
        ) : (
          <>
            {rated.length > 0 && (
              <ul className="skill-list">
                {rated.map((s) => (
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

            {unratedResume.length > 0 && (
              <SkillCloud
                label={rated.length > 0 ? 'From your resume, unrated' : 'From your resume'}
                skills={unratedResume}
                pickingConfidenceFor={pickingConfidenceFor}
                setPickingConfidenceFor={setPickingConfidenceFor}
                pickConfidence={pickConfidence}
                removeSkill={removeSkill}
              />
            )}
            {unratedHand.length > 0 && (
              <SkillCloud
                label={unratedResume.length > 0 ? 'Hand-added, unrated' : undefined}
                skills={unratedHand}
                pickingConfidenceFor={pickingConfidenceFor}
                setPickingConfidenceFor={setPickingConfidenceFor}
                pickConfidence={pickConfidence}
                removeSkill={removeSkill}
              />
            )}
          </>
        )}
      </Section>

      <Section
        title="Resume"
        hint="Optional. Upload a PDF or DOCX — skills join the list above, the rest stays here for you to trim. The file itself isn't stored."
      >
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
      </Section>

      <Section
        title="How you like to learn"
        hint="In your own words. The system reads it into a few traits — you decide what stays."
      >
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
          <Button variant="ghost" onClick={interpret} disabled={interpreting || !descRaw.trim()}>
            {interpreting ? 'Reading…' : 'Read into traits'}
          </Button>
        </div>
        {descTraits.length > 0 && (
          <ul className="trait-list">
            {descTraits.map((t) => (
              <li key={t}>
                <Chip onRemove={() => removeTrait(t)}>{t}</Chip>
              </li>
            ))}
          </ul>
        )}
      </Section>

      <Section
        title="Learning preferences"
        hint="Selectable, so generation can actually act on them — step sizing, resource choice. Pick what applies; skip what doesn't."
      >
        <div className="pref-groups">
          {LEARNING_PREFERENCE_GROUPS.map((group) => (
            <div className="pref-group" key={group.key}>
              <span className="pref-group-label">{group.label}</span>
              <div className="pref-group-options">
                {group.options.map((o) => (
                  <Chip
                    key={o.value}
                    toggle
                    tone="brass"
                    pressed={learningPreferences[group.key] === o.value}
                    onClick={() => setLearningPreference(group.key, o.value)}
                  >
                    {o.label}
                  </Chip>
                ))}
              </div>
            </div>
          ))}
        </div>
      </Section>

      <Section
        title="Learning formats"
        hint="Formats you'd rather avoid — a generated roadmap won't suggest resources in these."
      >
        <div className="format-options">
          {FORMATS.map((f) => (
            <Chip
              key={f.value}
              toggle
              tone="danger"
              pressed={avoidFormats.includes(f.value)}
              onClick={() => toggleAvoidFormat(f.value)}
            >
              {avoidFormats.includes(f.value) ? 'avoid: ' : ''}
              {f.label}
            </Chip>
          ))}
        </div>
      </Section>

      <Section
        title="What the system noticed"
        hint="Guesses from how you actually work — keep the ones that ring true. Nothing is used until you save."
      >
        <Button variant="ghost" onClick={analyze} disabled={analyzing}>
          {analyzing ? 'Looking…' : 'Analyze my sessions'}
        </Button>

        {proposal && (
          <div className="inferred-proposal">
            <p className="profile-section-hint">{proposal.basis}</p>
            {proposal.preferences.length === 0 ? (
              <p className="profile-empty">Nothing clear enough to suggest yet.</p>
            ) : (
              <ul className="inferred-candidates">
                {proposal.preferences.map((p) => (
                  <li className="inferred-candidate" key={p.text}>
                    <span>{p.text}</span>
                    <Button variant="ghost" onClick={() => keepPreference(p)}>
                      Keep
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {inferred.length > 0 && (
          <ul className="trait-list">
            {inferred.map((t) => (
              <li key={t}>
                <Chip onRemove={() => removeInferred(t)}>{t}</Chip>
              </li>
            ))}
          </ul>
        )}
      </Section>

      <div className="profile-actions">
        {error && <span className="profile-error">{error}</span>}
        {saved && <span className="profile-saved">Saved.</span>}
        {!saved && confirmedAt && <span className="profile-hint">Unsaved changes</span>}
        <Button variant="primary" onClick={save} disabled={saving}>
          {saving ? 'Saving…' : 'Save profile'}
        </Button>
      </div>
    </div>
  )
}

// A compact chip cloud for skills with no confidence set yet (Phase 23) — keeps a large resume
// import from turning into a wall of dropdown rows. Clicking a chip opens an inline confidence
// picker right there; picking one promotes the skill to the full row above.
function SkillCloud({ label, skills, pickingConfidenceFor, setPickingConfidenceFor, pickConfidence, removeSkill }) {
  return (
    <div className="skill-cloud-group">
      {label && <span className="skill-cloud-label">{label}</span>}
      <div className="skill-cloud">
        {skills.map((s) => (
          <div className="skill-chip-wrap" key={s.name}>
            <Chip
              onClick={() =>
                setPickingConfidenceFor((prev) => (prev === s.name ? null : s.name))
              }
              onRemove={() => removeSkill(s.name)}
            >
              {s.name}
            </Chip>
            {pickingConfidenceFor === s.name && (
              <div className="skill-confidence-picker">
                {CONFIDENCE.filter((c) => c.value).map((c) => (
                  <button
                    key={c.value}
                    className="skill-confidence-option"
                    onClick={() => pickConfidence(s.name, c.value)}
                  >
                    {c.label}
                  </button>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}
