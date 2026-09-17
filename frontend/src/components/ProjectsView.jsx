import { useState } from 'react'
import { patchEntry } from '../api'
import { Badge, ExternalLink } from './ui'
import './Roadmap.css'

// Project Portfolio checklist (Phase 24): every kind:"project" step across the whole tree,
// surfaced in one place for a career-scale roadmap — title, why it's there, a public-URL field
// to fill in once built, and the same completion checkbox the tree already uses (status, not a
// separate flag). A project step is still just a roadmap_step; this is a filtered view, not a
// new entity.
//
// Completion goes through the SAME handlers the tree row uses (`onMarkDone` → the verify gate
// when the roadmap has "check before done" on, `onUndoDone` for reopening) — a project checked
// off here must never silently self-report past a verification mode the founder turned on
// elsewhere. URL saving reports its own failure instead of dying as an unhandled rejection.
function collectProjects(nodes, moduleTitle = null, out = []) {
  for (const n of nodes) {
    if (n.children && n.children.length > 0) {
      collectProjects(n.children, n.type === 'roadmap' ? n.content?.title : moduleTitle, out)
    } else if (n.type === 'roadmap_step' && n.content?.kind === 'project') {
      out.push({ ...n, moduleTitle })
    }
  }
  return out
}

export default function ProjectsView({ roadmap, onChanged, onOpenStep, onMarkDone, onUndoDone }) {
  const projects = collectProjects(roadmap.children || [])

  if (projects.length === 0) {
    return <p className="projects-empty">No project steps yet — expand a module to find one.</p>
  }

  return (
    <ul className="projects-list">
      {projects.map((p) => (
        <ProjectRow
          key={p.id}
          project={p}
          onChanged={onChanged}
          onOpenStep={onOpenStep}
          onMarkDone={onMarkDone}
          onUndoDone={onUndoDone}
        />
      ))}
    </ul>
  )
}

function ProjectRow({ project, onChanged, onOpenStep, onMarkDone, onUndoDone }) {
  const [url, setUrl] = useState(project.content?.projectUrl || '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)
  const done = project.status === 'done'

  function toggleDone() {
    if (done) onUndoDone?.(project.id)
    else onMarkDone?.(project)
  }

  async function saveUrl() {
    if (saving || (project.content?.projectUrl || '') === url.trim()) return
    setSaving(true)
    setError(null)
    try {
      await patchEntry(project.id, { projectUrl: url.trim() })
      onChanged?.()
    } catch (err) {
      setError(err.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <li className={'project-row' + (done ? ' is-done' : '')}>
      <button
        className="project-check"
        onClick={toggleDone}
        aria-label={done ? 'Reopen project' : 'Mark project done'}
      >
        {done ? '✓' : '○'}
      </button>
      <div className="project-body">
        <div className="project-head">
          <span className="project-text" onDoubleClick={() => onOpenStep?.(project.id)}>
            {project.content?.text}
          </span>
          {project.moduleTitle && <Badge>{project.moduleTitle}</Badge>}
        </div>
        {project.content?.rationale && <p className="project-rationale">{project.content.rationale}</p>}
        <div className="project-url-row">
          <input
            className="step-input project-url-input"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            onBlur={saveUrl}
            placeholder="Public URL once built (repo, live link, write-up)…"
          />
          {url.trim() && !saving && (
            <ExternalLink href={url.trim()} className="project-url-link">
              open
            </ExternalLink>
          )}
        </div>
        {error && <p className="roadmap-error">{error}</p>}
      </div>
    </li>
  )
}
