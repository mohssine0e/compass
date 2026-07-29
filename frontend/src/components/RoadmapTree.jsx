import { dependencyInfo, nodeText } from '../roadmapTree'
import { truncateAtWord } from '../text'
import { Badge, Button, IconDelete, IconEdit, IconModule, IconStep, IconSubSubstep, IconSubstep, IconUndo, Menu } from './ui'

// One icon per nesting depth (Phase 21) — module boldest, sub-substep faintest.
const DEPTH_ICONS = [IconModule, IconStep, IconSubstep, IconSubSubstep]

// A single leaf step row: marker, text (double-click opens the deep view), inline edit mode, and
// the mark-done / menu actions. `ctx` bundles the state and handlers shared across the whole tree
// (built once per render in RoadmapDetail as `treeCtx`) so this stays an explicit prop instead of
// an implicit closure over the parent component.
function StepRow({ node, depth, parentType, ctx }) {
  const isCurrent = node.id === ctx.currentId
  const isDone = node.status === 'done'
  const isDropped = node.status === 'dropped'
  const state = isDone ? 'is-done' : isDropped ? 'is-dropped' : isCurrent ? 'is-current' : 'is-upcoming'
  const isEditing = ctx.editingStepId === node.id
  // RB-4.9: a same-module dependency that isn't done yet blocks completion; a cross-module one
  // is a reminder only — the founder can still complete the step regardless.
  const dep = dependencyInfo(node, ctx.nodeIndex)
  const blocked = Boolean(dep && !dep.done && !dep.crossModule)
  const menuItems = [
    { label: 'Edit', onClick: () => ctx.startEdit(node), icon: <IconEdit /> },
    { label: 'Break down', onClick: () => ctx.startBreakDown(node) },
    ...(depth === 0 ? [{ label: 'Insert step above', onClick: () => ctx.startInsert(node.orderIndex) }] : []),
    ...(parentType === 'roadmap_step'
      ? [{ label: 'Graduate (move up a level)', onClick: () => ctx.graduateStepAction(node) }]
      : []),
    ...(isDone ? [{ label: 'Undo', onClick: () => ctx.undoStep(node.id), icon: <IconUndo /> }] : []),
    { label: 'Delete', onClick: () => ctx.deleteStep(node), danger: true, icon: <IconDelete /> },
  ]
  return (
    <li
      id={`roadmap-node-${node.id}`}
      className={`step-item ${state} depth-${Math.min(depth, 3)}` + (ctx.searchMatchIds?.has(node.id) ? ' is-search-match' : '')}
      style={depth ? { marginLeft: depth * 30 } : undefined}
    >
      <span className="step-marker" aria-hidden="true">
        {isDone ? '✓' : isDropped ? '–' : isCurrent ? '●' : '○'}
      </span>
      {isEditing ? (
        <input
          className="step-edit-input"
          value={ctx.editText}
          onChange={(e) => ctx.setEditText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') ctx.saveEdit(node.id)
            if (e.key === 'Escape') ctx.cancelEdit()
          }}
          autoFocus
        />
      ) : (
        <span
          className="step-text step-text-openable"
          onDoubleClick={() => ctx.dispatchPanel({ type: 'deepView', stepId: node.id })}
          title="Double-click for details"
        >
          <span className="step-text-main">{truncateAtWord(node.content?.text)}</span>
          {(node.content?.kind === 'project' ||
            node.content?.weight ||
            node.content?.skeletonOnly ||
            dep) && (
            <span className="step-tags">
              {node.content?.skeletonOnly && (
                <Badge tone="danger" title="Every AI provider was unavailable when this was drafted — details fill in on their own once one recovers.">
                  basic outline — details pending
                </Badge>
              )}
              {node.content?.kind === 'project' && <Badge tone="brass">project</Badge>}
              {node.content?.weight && node.content.weight !== 'medium' && (
                <Badge>{node.content.weight}</Badge>
              )}
              {dep && (
                <span
                  className={'step-needs' + (blocked ? ' is-blocked' : '')}
                  title={
                    blocked
                      ? `Blocked until "${dep.text}" is done`
                      : dep.crossModule
                        ? `Depends on "${dep.text}" from a different module — reminder only, not a blocker`
                        : undefined
                  }
                >
                  {blocked ? '🔒' : '⛓️'} needs: {dep.text}
                </span>
              )}
            </span>
          )}
        </span>
      )}
      {isEditing ? (
        <span className="step-edit-actions">
          <Button variant="ghost" onClick={() => ctx.saveEdit(node.id)} disabled={ctx.savingEdit || !ctx.editText.trim()}>
            {ctx.savingEdit ? 'Saving…' : 'Save'}
          </Button>
          <Button variant="ghost" onClick={ctx.cancelEdit} disabled={ctx.savingEdit}>
            Cancel
          </Button>
        </span>
      ) : (
        <span className="step-actions">
          {isCurrent && (
            <Button
              variant="primary"
              onClick={() => ctx.requestMarkDone(node)}
              disabled={ctx.busyStepId === node.id || blocked}
              title={blocked ? `Blocked until "${dep.text}" is done` : undefined}
            >
              {ctx.busyStepId === node.id ? 'Marking…' : blocked ? 'Blocked' : 'Mark done'}
            </Button>
          )}
          <Menu items={menuItems} label={`Actions for ${nodeText(node)}`} />
        </span>
      )}
    </li>
  )
}

// A container node — a module (child roadmap) or a step with substeps. Collapsible, with its own
// rolled-up progress. A step-turned-container (from a break-down) gets a "Flatten" action to
// promote back up (Phase 20) — modules use a different mechanism (expand), not this.
function GroupNode({ node, depth, parentType, ctx }) {
  const open = !ctx.collapsed.has(node.id)
  const p = node.progress || { done: 0, total: 0 }
  const isStepContainer = node.type === 'roadmap_step'
  const DepthIcon = DEPTH_ICONS[Math.min(depth, DEPTH_ICONS.length - 1)]
  return (
    <>
      <li
        id={`roadmap-node-${node.id}`}
        className={`node-group depth-${Math.min(depth, 3)}` + (ctx.searchMatchIds?.has(node.id) ? ' is-search-match' : '')}
        style={depth ? { marginLeft: depth * 30 } : undefined}
        onClick={() => ctx.toggleCollapsed(node.id)}
      >
        <span className="node-group-caret" aria-hidden="true">{open ? '▾' : '▸'}</span>
        <span className="node-group-depth-icon" aria-hidden="true">
          <DepthIcon size={14} />
        </span>
        <span className="node-group-title">{nodeText(node)}</span>
        {node.missingProjectFlag && (
          <Badge tone="danger" title="Career-scale roadmap, no project step here yet">
            no project step
          </Badge>
        )}
        <Badge>{p.done}/{p.total}</Badge>
        {isStepContainer && (
          <span onClick={(e) => e.stopPropagation()}>
            <Menu
              label={`Actions for ${nodeText(node)}`}
              items={[{ label: 'Flatten (remove substeps)', onClick: () => ctx.flattenStepAction(node), danger: true }]}
            />
          </span>
        )}
      </li>
      {open && node.children.map((child) => (
        <NodeRenderer key={child.id} node={child} depth={depth + 1} parentType={node.type} ctx={ctx} />
      ))}
    </>
  )
}

// A module (child roadmap) that hasn't been expanded into steps yet (Phase 13) — its own row with
// the module's scope and an explicit "Expand" action, instead of being treated as a leaf. Most
// modules are already drafting (or done) in the background from the moment they appear — reflect
// that instead of always offering a button that blocks on a fresh AI call.
function EmptyModuleNode({ node, depth, ctx }) {
  const selected = ctx.selectedModuleIds.has(node.id)
  const job = ctx.prefetch[node.id]
  const isPending = job?.status === 'PENDING'
  const isDone = job?.status === 'DONE'
  const stepCount = isDone ? (job.result?.steps?.length ?? 0) : 0
  return (
    <li id={`roadmap-node-${node.id}`} className="node-group node-group-empty" style={depth ? { marginLeft: depth * 30 } : undefined}>
      <input
        type="checkbox"
        className="node-group-select"
        checked={selected}
        disabled={!!job}
        title={job ? 'Already drafting in the background — no need to batch-select this one' : undefined}
        aria-label={`Select ${nodeText(node)} for batch expansion`}
        onChange={(e) => {
          ctx.setSelectedModuleIds((prev) => {
            const next = new Set(prev)
            if (e.target.checked) next.add(node.id)
            else next.delete(node.id)
            return next
          })
        }}
      />
      <span className="node-group-caret" aria-hidden="true">·</span>
      <span className="node-group-title">
        {nodeText(node)}
        {node.content?.scope && <span className="node-group-scope"> — {node.content.scope}</span>}
      </span>
      <Button variant="ghost" onClick={() => ctx.dispatchPanel({ type: 'regenerateModule', moduleId: node.id })}>
        Regenerate scope
      </Button>
      {isPending ? (
        <span className="node-group-working" aria-live="polite">Working on it…</span>
      ) : (
        <Button variant="ghost" onClick={() => ctx.dispatchPanel({ type: 'expandModule', moduleId: node.id })}>
          {isDone ? `Review ${stepCount} step${stepCount === 1 ? '' : 's'}` : 'Expand this module'}
        </Button>
      )}
    </li>
  )
}

// Entry point for rendering one node of the roadmap tree, recursing into its children as needed.
// `ctx` bundles all the state/handlers this subtree needs from RoadmapDetail (see its `treeCtx`)
// so this file has no implicit closure over the parent component — everything is an explicit prop.
export function NodeRenderer({ node, depth, parentType, ctx }) {
  if (node.type === 'roadmap') {
    return node.children && node.children.length > 0
      ? <GroupNode node={node} depth={depth} parentType={parentType} ctx={ctx} />
      : <EmptyModuleNode node={node} depth={depth} ctx={ctx} />
  }
  if (node.children && node.children.length > 0) {
    return <GroupNode node={node} depth={depth} parentType={parentType} ctx={ctx} />
  }
  return <StepRow node={node} depth={depth} parentType={parentType} ctx={ctx} />
}
