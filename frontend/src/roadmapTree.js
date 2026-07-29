// Pure tree-logic helpers shared between RoadmapDetail (the page) and RoadmapTree (the
// tree-view renderer) — no JSX, no hooks, so these stay trivially unit-testable
// (see components/__tests__/RoadmapDetail.treeLogic.test.js) independent of rendering.

// A roadmap is a tree (Phase 13): a flat roadmap is one level of leaf steps and reads as a plain
// list; a big one nests modules (child roadmaps) and substeps. Progress and "current step" come
// from the backend, rolled up over leaf steps wherever they sit, so this view just renders.
export const nodeText = (node) =>
  node.type === 'roadmap' ? node.content?.title : node.content?.text

// Container node ids that are fully complete — collapsed by default so a big roadmap opens
// anchored on the modules still in play.
export function fullyDoneGroups(nodes, out = []) {
  for (const n of nodes) {
    if (n.children && n.children.length > 0) {
      if (n.progress && n.progress.total > 0 && n.progress.done === n.progress.total) {
        out.push(n.id)
      }
      fullyDoneGroups(n.children, out)
    }
  }
  return out
}

// Collapse every container except the one the current step is actually inside — a nested
// roadmap opens showing just the module you're in, right now (Phase 21 / RB-4.10's CAREER
// default). Falls back to collapsing what's fully complete if there's no current step (e.g.
// everything's already done).
export function collapseToCurrentModule(data) {
  const children = data.children || []
  if (data.progress?.currentStepId == null) {
    return new Set(fullyDoneGroups(children))
  }
  const groups = []
  const collectGroups = (nodes) => {
    for (const n of nodes) {
      if (n.children && n.children.length > 0) {
        groups.push(n.id)
        collectGroups(n.children)
      }
    }
  }
  collectGroups(children)
  const onPath = new Set(
    (findNodePath(children, data.progress.currentStepId) || []).map((n) => n.id)
  )
  return new Set(groups.filter((gid) => !onPath.has(gid)))
}

// Default collapse on first load, per tier (RB-4.10) — TASK/MINI are flat by nature so there's
// nothing to collapse either way; TOPIC only auto-collapses what's fully done; CAREER anchors to
// the current module. Unknown tier (a roadmap from before RB-2, or a failed classification)
// falls back to today's pre-RB-4 behavior. The founder's own manual choices (RB-4.10's
// `collapseOverrides`, persisted server-side) always win over whatever the default computed.
export function seedCollapsed(data) {
  const children = data.children || []
  let base
  if (data.shape !== 'nested') {
    base = new Set()
  } else if (data.tier === 'TOPIC') {
    base = new Set(fullyDoneGroups(children))
  } else if (data.tier === 'CAREER' || data.tier == null) {
    base = collapseToCurrentModule(data)
  } else {
    base = new Set()
  }
  for (const [idStr, isCollapsed] of Object.entries(data.collapseOverrides || {})) {
    const id = Number(idStr)
    if (isCollapsed) base.add(id)
    else base.delete(id)
  }
  return base
}

// Real time invested, rolled up across every leaf's session history in the tree — the
// CLAUDE.md-sanctioned alternative to a streak ("total sessions or time invested, never an
// unbroken streak"). Same "only count a session with a real logged duration" rule StepDeepView
// already uses per-step; this just sums it roadmap-wide instead of one step at a time.
export function sessionStats(nodes) {
  let totalMinutes = 0
  let sessionCount = 0
  for (const n of nodes) {
    if (n.children && n.children.length > 0) {
      const nested = sessionStats(n.children)
      totalMinutes += nested.totalMinutes
      sessionCount += nested.sessionCount
    } else if (Array.isArray(n.content?.sessionHistory)) {
      for (const s of n.content.sessionHistory) {
        if (s.durationMinutes != null) {
          totalMinutes += s.durationMinutes
          sessionCount += 1
        }
      }
    }
  }
  return { totalMinutes, sessionCount }
}

// True if any module anywhere in the tree has no steps of its own yet — worth polling
// background-draft status for. Once every module's expanded, this goes false and polling stops.
export function hasEmptyModule(nodes) {
  for (const n of nodes) {
    if (n.type === 'roadmap' && (!n.children || n.children.length === 0)) return true
    if (n.children && n.children.length > 0 && hasEmptyModule(n.children)) return true
  }
  return false
}

// RB-4.9: node + its direct parent id, by id, across the whole tree — lets a dependency edge be
// checked for "done?" and "same module as the step that depends on it?" without a server round
// trip (everything needed is already in the loaded roadmap tree).
export function nodeIndexOf(nodes, parentId = null, map = new Map()) {
  for (const n of nodes) {
    map.set(n.id, { node: n, parentId })
    if (n.children) nodeIndexOf(n.children, n.id, map)
  }
  return map
}

// Only real step->step dependencies count for blocking — a step depending on something from a
// different module is a reminder, never a gate (RB-4.9).
export function dependencyInfo(node, nodeIndex) {
  if (!node.dependsOn) return null
  const dep = nodeIndex.get(node.dependsOn)
  const self = nodeIndex.get(node.id)
  if (!dep || !self) return null
  return {
    text: nodeText(dep.node),
    done: dep.node.status === 'done',
    crossModule: dep.parentId !== self.parentId,
  }
}

// The estimated-time rollup (Phase 18) is minutes; render it the way the resource estimates
// that feed it are already written ("~1h 30 min", "~45 min").
export function formatMinutes(total) {
  const hours = Math.floor(total / 60)
  const minutes = total % 60
  if (hours === 0) return `${minutes} min`
  if (minutes === 0) return `${hours}h`
  return `${hours}h ${minutes} min`
}

// Ids of every node (module, step, or substep — any depth) whose own text contains `query`,
// case-insensitive, in tree order. Matches on a node's own text only, not its descendants' — a
// module doesn't "match" just because something inside it does; that's what auto-expanding the
// ancestor chain of an actual match is for (see RoadmapDetail's search handling).
export function searchMatches(nodes, query) {
  const q = query.trim().toLowerCase()
  if (!q) return []
  const out = []
  const walk = (list) => {
    for (const n of list) {
      if ((nodeText(n) || '').toLowerCase().includes(q)) out.push(n.id)
      if (n.children) walk(n.children)
    }
  }
  walk(nodes)
  return out
}

export function findNode(nodes, targetId) {
  for (const n of nodes) {
    if (n.id === targetId) return n
    if (n.children) {
      const found = findNode(n.children, targetId)
      if (found) return found
    }
  }
  return null
}

// Ancestor chain from the top level down to (excluding) the target node, in order — feeds the
// deep view's breadcrumb (Phase 21). Null if the target isn't in the tree.
export function findNodePath(nodes, targetId, trail = []) {
  for (const n of nodes) {
    if (n.id === targetId) return trail
    if (n.children) {
      const found = findNodePath(n.children, targetId, [...trail, n])
      if (found) return found
    }
  }
  return null
}

// Nesting depth of a node (0 = top-level, matching NodeRenderer's depth prop) — used to disable
// "break it down" once a step is already at the substep nesting cap (Phase 20).
export function findNodeDepth(nodes, targetId, depth = 0) {
  for (const n of nodes) {
    if (n.id === targetId) return depth
    if (n.children) {
      const found = findNodeDepth(n.children, targetId, depth + 1)
      if (found != null) return found
    }
  }
  return null
}
