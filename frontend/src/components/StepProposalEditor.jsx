import { useState } from 'react'
import { Badge, Button, ExternalLink } from './ui'
import './GenerateRoadmapScreen.css'

// Stable client ids for proposed steps/resources, so dependency links and React keys survive
// edits/removals. Shared by any screen that edits an AI step proposal (Phase 4/13).
let cidCounter = 0
function nextCid() {
  cidCounter += 1
  return cidCounter
}

/**
 * Turn the steps a backend proposal returned (dependsOn as an index) into the editor's
 * cid-keyed shape (dependsOn as a stable client id), so edits/removals don't scramble links.
 */
export function fromProposedSteps(rawSteps) {
  const raw = rawSteps && rawSteps.length ? rawSteps : [{ text: '' }]
  const withIds = raw.map((s, i) => ({ cid: nextCid(), ...s, _index: i }))
  return withIds.map((s) => ({
    cid: s.cid,
    text: s.text || '',
    kind: s.kind || 'concept',
    weight: s.weight || 'medium',
    rationale: s.rationale || null,
    dependsOnCid: s.dependsOn != null && withIds[s.dependsOn] ? withIds[s.dependsOn].cid : null,
    // A prerequisite from an EARLIER module (Phase 18) — a real id outside this batch, so it
    // isn't re-linkable via dependsOnCid; shown read-only instead.
    crossModuleDependsOnId: s.dependsOnEntryId ?? null,
    crossModuleDependsOnText: s.dependsOnEntryText || null,
    resources: (s.resources || []).map((r) => ({ rcid: nextCid(), ...r })),
  }))
}

/**
 * The inverse: editor steps → the flat draftSteps shape the create/accept endpoints expect.
 * `skeletonOnly` (Phase 19) marks steps accepted from the emergency titles-only fallback, so the
 * backend can flag them for the background retry that fills in full detail later.
 */
export function toDraftSteps(steps, skeletonOnly = false) {
  const kept = steps.filter((s) => s.text.trim())
  const indexOfCid = new Map(kept.map((s, i) => [s.cid, i]))
  return kept.map((s) => ({
    text: s.text.trim(),
    kind: s.kind,
    weight: s.weight,
    dependsOn: s.dependsOnCid != null && indexOfCid.has(s.dependsOnCid)
      ? indexOfCid.get(s.dependsOnCid)
      : null,
    dependsOnEntryId: s.crossModuleDependsOnId ?? null,
    resources: (s.resources || []).map((r) => ({
      title: r.title,
      url: r.url,
      format: r.format,
      sourceType: r.sourceType,
      estimatedTime: r.estimatedTime,
      aiGroundingSource: r.aiGroundingSource,
    })),
    skeletonOnly,
  }))
}

export function newBlankStep() {
  return { cid: nextCid(), text: '', kind: 'concept', weight: 'medium', rationale: null, dependsOnCid: null }
}

/**
 * Map a Phase 20 self-critique result's `stepIndex` (into the original proposal array) onto the
 * editor's stable `cid`s — call once, right after `fromProposedSteps`, before any edits/removals
 * can happen to `editorSteps`. Issues without a `stepIndex` (plan-wide) keep `cid: null`.
 */
export function attachIssueCids(editorSteps, issues) {
  return (issues || []).map((issue) => ({
    ...issue,
    cid: issue.stepIndex != null ? editorSteps[issue.stepIndex]?.cid ?? null : null,
  }))
}

/**
 * An editable list of proposed steps — text, kind/weight badges, prerequisite label, rationale,
 * and curated resources. Fully controlled: `steps` in, `onChange(nextSteps)` out. Shared by any
 * "propose → approve → apply" AI drafting flow that proposes steps (Phase 4 roadmap generation,
 * Phase 13 module expansion) so this editor is built once, not per screen.
 *
 * @param {Array} steps - editor-shaped steps (see fromProposedSteps)
 * @param {(next: Array) => void} onChange
 * @param {string[]} [skipped] - topics skipped based on the profile, shown above the list
 * @param {string[]} [sources] - grounding sources, shown below the list
 * @param {boolean} [skeletonOnly] - true when every AI provider failed and this is the
 *   emergency titles-only fallback (Phase 19) — no descriptions/resources, filled in later
 * @param {Array} [issues] - Phase 20 self-critique issues, already cid-mapped (see
 *   `attachIssueCids`) — {severity, message, cid, suggestedFix}. The founder accepts (applies
 *   `suggestedFix` to that step, same as editing it by hand) or dismisses each one.
 */
export default function StepProposalEditor({
  steps,
  onChange,
  skipped = [],
  sources = [],
  skeletonOnly = false,
  issues = [],
}) {
  const [dismissed, setDismissed] = useState(new Set())
  function update(updater) {
    onChange(typeof updater === 'function' ? updater(steps) : updater)
  }
  function setStepText(cid, value) {
    update((prev) => prev.map((s) => (s.cid === cid ? { ...s, text: value } : s)))
  }
  function acceptIssue(issue, key) {
    if (issue.suggestedFix && issue.cid != null) {
      setStepText(issue.cid, issue.suggestedFix)
    }
    setDismissed((prev) => new Set(prev).add(key))
  }
  function dismissIssue(key) {
    setDismissed((prev) => new Set(prev).add(key))
  }
  function addStep() {
    update((prev) => [...prev, newBlankStep()])
  }
  function removeStep(cid) {
    update((prev) =>
      prev.length > 1
        ? prev
            .filter((s) => s.cid !== cid)
            .map((s) => (s.dependsOnCid === cid ? { ...s, dependsOnCid: null } : s))
        : prev
    )
  }
  function updateStepResources(cid, updater) {
    update((prev) => prev.map((s) => (s.cid === cid ? { ...s, resources: updater(s.resources || []) } : s)))
  }
  function removeResource(cid, rcid) {
    updateStepResources(cid, (rs) => rs.filter((r) => r.rcid !== rcid))
  }
  function moveResource(cid, rcid, dir) {
    updateStepResources(cid, (rs) => {
      const i = rs.findIndex((r) => r.rcid === rcid)
      const j = i + dir
      if (i < 0 || j < 0 || j >= rs.length) return rs
      const next = [...rs]
      ;[next[i], next[j]] = [next[j], next[i]]
      return next
    })
  }
  function addOwnResource(cid, title, url) {
    const t = title.trim()
    const u = url.trim()
    if (!t || !u) return
    updateStepResources(cid, (rs) => [
      ...rs,
      { rcid: nextCid(), title: t, url: u, format: 'written', sourceType: 'community' },
    ])
  }

  const textByCid = new Map(steps.map((s) => [s.cid, s.text]))

  return (
    <>
      {skeletonOnly && (
        <p className="gen-lead">
          <Badge tone="danger">basic outline — details pending</Badge> Every AI provider was
          unavailable, so these are titles only — no descriptions or resources yet. They'll fill
          in on their own once a provider recovers.
        </p>
      )}
      {skipped.length > 0 && (
        <div className="gen-skipped">
          <span className="gen-skipped-label">Skipped, based on your profile:</span>
          <ul className="gen-skipped-list">
            {skipped.map((s, i) => (
              <li key={i}>{s}</li>
            ))}
          </ul>
        </div>
      )}
      {issues.some((_, i) => !dismissed.has(i)) && (
        <div className="gen-issues">
          <span className="gen-issues-label">The system noticed:</span>
          {issues.map((issue, i) =>
            dismissed.has(i) ? null : (
              <div className="gen-issue" key={i}>
                <Badge tone={issue.severity === 'HIGH' ? 'danger' : 'default'}>
                  {(issue.severity || 'low').toLowerCase()}
                </Badge>
                <span className="gen-issue-message">{issue.message}</span>
                {issue.suggestedFix && (
                  <p className="gen-issue-fix">Suggested: "{issue.suggestedFix}"</p>
                )}
                <span className="gen-issue-actions">
                  <button className="step-edit-btn" onClick={() => acceptIssue(issue, i)}>
                    Accept
                  </button>
                  <button className="step-edit-btn" onClick={() => dismissIssue(i)}>
                    Dismiss
                  </button>
                </span>
              </div>
            )
          )}
        </div>
      )}
      <div className="roadmap-steps">
        {steps.map((step, i) => (
          <div className="gen-step" key={step.cid}>
            <div className="step-row">
              <span className="step-index">{i + 1}</span>
              <input
                className="step-input"
                value={step.text}
                onChange={(e) => setStepText(step.cid, e.target.value)}
                placeholder={`Step ${i + 1}`}
              />
              <button
                type="button"
                className="step-remove"
                onClick={() => removeStep(step.cid)}
                aria-label={`Remove step ${i + 1}`}
                disabled={steps.length <= 1}
              >
                ×
              </button>
            </div>
            <div className="gen-step-meta">
              {step.kind && <Badge tone={step.kind === 'project' ? 'brass' : 'default'}>{step.kind}</Badge>}
              {step.weight && <Badge>{step.weight}</Badge>}
              {step.dependsOnCid != null && textByCid.get(step.dependsOnCid) && (
                <span className="gen-depends">needs: {textByCid.get(step.dependsOnCid)}</span>
              )}
              {step.crossModuleDependsOnText && (
                <span className="gen-depends gen-depends-cross">
                  needs (earlier module): {step.crossModuleDependsOnText}
                </span>
              )}
            </div>
            {step.rationale && <p className="gen-rationale">{step.rationale}</p>}
            <StepResources
              step={step}
              onRemove={(rcid) => removeResource(step.cid, rcid)}
              onMove={(rcid, dir) => moveResource(step.cid, rcid, dir)}
              onAdd={(title, url) => addOwnResource(step.cid, title, url)}
            />
          </div>
        ))}
        <Button type="button" variant="ghost" className="step-add" onClick={addStep}>
          + Add step
        </Button>
      </div>
      {sources.length > 0 && (
        <div className="gen-sources">
          <span className="gen-sources-label">Grounded in:</span>
          <ul className="gen-sources-list">
            {sources.map((s, i) => (
              <li key={i}>{s}</li>
            ))}
          </ul>
        </div>
      )}
    </>
  )
}

// Curated resources for one proposed step (Phase 7.5): remove, reorder, or add your own.
function StepResources({ step, onRemove, onMove, onAdd }) {
  const [adding, setAdding] = useState(false)
  const [title, setTitle] = useState('')
  const [url, setUrl] = useState('')
  const resources = step.resources || []

  function submit() {
    onAdd(title, url)
    setTitle('')
    setUrl('')
    setAdding(false)
  }

  return (
    <div className="gen-resources">
      {resources.map((r, i) => (
        <div className="gen-resource" key={r.rcid}>
          <ExternalLink href={r.url}>{r.title}</ExternalLink>
          <span className="gen-resource-meta">
            {r.format && <Badge>{r.format}</Badge>}
            {r.estimatedTime && <span className="gen-resource-time">{r.estimatedTime}</span>}
          </span>
          <span className="gen-resource-actions">
            <button
              type="button"
              className="step-move-btn"
              onClick={() => onMove(r.rcid, -1)}
              disabled={i === 0}
              aria-label="Move resource up"
            >
              ↑
            </button>
            <button
              type="button"
              className="step-move-btn"
              onClick={() => onMove(r.rcid, 1)}
              disabled={i === resources.length - 1}
              aria-label="Move resource down"
            >
              ↓
            </button>
            <button
              type="button"
              className="step-remove"
              onClick={() => onRemove(r.rcid)}
              aria-label="Remove resource"
            >
              ×
            </button>
          </span>
        </div>
      ))}
      {adding ? (
        <div className="gen-resource-add">
          <input
            className="step-input"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Resource title"
            autoFocus
          />
          <input
            className="step-input"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder="https://…"
          />
          <button className="step-edit-btn" onClick={submit} disabled={!title.trim() || !url.trim()}>
            Add
          </button>
          <button className="step-edit-btn" onClick={() => setAdding(false)}>
            Cancel
          </button>
        </div>
      ) : (
        <button type="button" className="gen-resource-addbtn" onClick={() => setAdding(true)}>
          + Add a resource
        </button>
      )}
    </div>
  )
}
