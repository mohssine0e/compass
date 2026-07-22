import { Fragment } from 'react'
import { truncateAtWord } from '../text'
import { Badge, ExternalLink } from './ui'
import './Roadmap.css'

// The learning path (Phase 13): an explicit ordered traversal — the leaf step you're on, the
// next few after it, their resources, and estimated time — distinct from the structural tree
// view. Ties into Focus / guided sessions: this is "what's next", not "how it's organized".
const HOW_MANY_AHEAD = 4

// Leaf roadmap_step nodes in tree (pre-order) order, matching the backend's leaf traversal.
// Each leaf carries the title of the module it sits under (Phase 22) — null in a flat roadmap.
function flattenLeaves(nodes, moduleTitle = null, out = []) {
  for (const n of nodes) {
    if (n.children && n.children.length > 0) {
      flattenLeaves(n.children, n.type === 'roadmap' ? n.content?.title : moduleTitle, out)
    } else if (n.type === 'roadmap_step') {
      out.push({ ...n, moduleTitle })
    }
  }
  return out
}

export default function LearningPathView({ roadmap, onOpenStep }) {
  const leaves = flattenLeaves(roadmap.children || [])
  const currentIndex = leaves.findIndex((l) => l.id === roadmap.progress.currentStepId)

  if (currentIndex === -1) {
    return <p className="path-empty">All steps done — nothing left on the path.</p>
  }

  const upcoming = leaves.slice(currentIndex, currentIndex + 1 + HOW_MANY_AHEAD)

  return (
    <ol className="learning-path">
      {upcoming.map((step, i) => (
        <Fragment key={step.id}>
        {step.moduleTitle && (i === 0 || upcoming[i - 1].moduleTitle !== step.moduleTitle) && (
          <li className="path-module-label">{step.moduleTitle}</li>
        )}
        <li
          className={'path-step' + (i === 0 ? ' is-current' : '')}
          onDoubleClick={() => onOpenStep?.(step.id)}
        >
          <span className="path-step-marker" aria-hidden="true">{i === 0 ? '●' : '○'}</span>
          <div className="path-step-body">
            <p className="path-step-text">{truncateAtWord(step.content?.text)}</p>
            <div className="path-step-meta">
              {step.content?.skeletonOnly && (
                <Badge tone="danger">basic outline — details pending</Badge>
              )}
              {step.content?.kind === 'project' && <Badge tone="brass">project</Badge>}
              {step.content?.weight && step.content.weight !== 'medium' && (
                <Badge>{step.content.weight}</Badge>
              )}
            </div>
            {step.content?.resources && step.content.resources.length > 0 && (
              <ul className="path-step-resources">
                {step.content.resources.map((r) => (
                  <li key={r.id || r.url} className="path-step-resource">
                    <ExternalLink href={r.url}>{r.title}</ExternalLink>
                    {r.estimatedTime && <span className="path-step-time">{r.estimatedTime}</span>}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </li>
        </Fragment>
      ))}
      {currentIndex + 1 + HOW_MANY_AHEAD < leaves.length && (
        <li className="path-more">…{leaves.length - (currentIndex + 1 + HOW_MANY_AHEAD)} more after this</li>
      )}
    </ol>
  )
}
