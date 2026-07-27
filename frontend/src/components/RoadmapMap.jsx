import { useCallback, useEffect, useLayoutEffect, useMemo, useReducer, useRef } from 'react'
import {
  backfillResources,
  downloadRoadmapExport,
  getModulePrefetchStatus,
  getRoadmap,
  getStepCheck,
  getStepDefaultFormat,
  patchEntry,
  verifyStep,
} from '../api'
import ExpandModuleModal from './ExpandModuleModal'
import ReformulatePanel from './ReformulatePanel'
import '../design/atlas.css'
import './RoadmapMap.css'

/**
 * The roadmap as a route you travel, not a document you read.
 *
 * The previous attempt was a tree beside a text column, which is really a table of contents
 * beside an article — it told you what a step *says* but never showed you the shape of the
 * thing, how far in you are, or how much road is left. A 58-step career roadmap should look
 * long. That's information.
 *
 * So: a serpentine route. Every leaf step is a station in order, wrapping left-to-right then
 * right-to-left, so the whole roadmap is one continuous line you can take in at a glance.
 * Modules are legs, coloured like transit lines. Track you've covered is solid; track ahead is
 * faint. Selecting a station opens a card docked over the map rather than navigating away, so
 * you never lose your place on the route.
 *
 * Geometry is pure maths from an observed container width — no DOM measuring per node, so it
 * stays stable while the detail card animates in and out.
 */

const ROW_H = 158 // vertical pitch: enough that a label never crowds the row below
const PAD_Y = 56 // breathing room above the first row / below the last

// Station size carries effort. A roadmap where every stop looks identical hides the fact that
// three of them are afternoons and one is a fortnight.
const R_BY_WEIGHT = { small: 6.5, medium: 9, large: 11.5 }
const R = 9
const radiusOf = (node) => R_BY_WEIGHT[node.content?.weight] ?? R

/**
 * How a stop reads on the map. The distinction that matters most here is the one the whole
 * product exists for: CLAUDE.md opens with "self-reported progress lies", so a step you ticked
 * and a step you actually defended must not look the same. `claimed` is filled but hollow-cored
 * and dashed — progress, provisionally. `verified` is solid with a tick.
 */
function stopState(node, now) {
  if (node.status === 'dropped') return 'dropped'
  if (node.status === 'done') {
    const recheckAt = node.content?.nextRecheckAt
    if (recheckAt && Date.parse(recheckAt) <= now) return 'due'
    return node.content?.verifiedAt ? 'verified' : 'claimed'
  }
  return 'todo'
}

/**
 * Which legs are open. The default is "only the one you're working in" — a 58-stop career
 * roadmap shown in full is a wall, and the legs you finished months ago earn no space. Explicit
 * choices win over that default and are persisted on the roadmap (`collapseOverrides`, RB-4.10),
 * so the map opens the way you left it rather than resetting every visit.
 */
function openLegs(legs, overrides, currentLegIndex) {
  const open = new Set()
  legs.forEach((leg, i) => {
    if (leg.unbuilt) return // nothing to open into yet
    const override = overrides?.[String(leg.id)]
    if (override === false) open.add(i) // explicitly expanded
    else if (override === true) return // explicitly collapsed
    else if (i === currentLegIndex) open.add(i) // default: where you are
  })
  return open
}

/**
 * The route as it is actually drawn: an open leg contributes each of its stops, a collapsed one
 * contributes a single bundle. Layout walks this, not the raw stop list, so collapsing genuinely
 * shortens the line instead of just hiding markers on it.
 */
function toItems(stations, legs, open) {
  const items = []
  legs.forEach((leg, legIndex) => {
    if (open.has(legIndex)) {
      for (let i = leg.start; i < leg.start + leg.count; i++) {
        items.push({ kind: 'stop', station: stations[i], index: i, legIndex })
      }
    } else {
      items.push({ kind: 'leg', leg, legIndex })
    }
  })
  return items
}

const LENSES = [
  { id: 'all', label: 'Everything' },
  { id: 'remaining', label: 'Remaining' },
  { id: 'projects', label: 'Projects' },
  { id: 'unproven', label: 'Unproven' },
]

function matchesLens(node, lens, state) {
  if (lens === 'remaining') return state === 'todo' || state === 'due'
  if (lens === 'projects') return node.content?.kind === 'project'
  if (lens === 'unproven') return state === 'claimed' || state === 'due'
  return true
}

const initialState = {
  roadmap: null,
  selectedId: null,
  lens: 'all',
  overrides: {},
  check: null,
  busy: false,
  error: null,
  // Background-draft status per unbuilt module, so opening one that's already drafted skips
  // straight to review instead of asking for it a second time.
  prefetch: {},
  expandingLegId: null,
  reformulating: false,
  notice: null,
}

function reducer(state, action) {
  switch (action.type) {
    case 'loaded':
      return {
        ...state,
        roadmap: action.roadmap,
        error: null,
        // Server-persisted collapse state is the starting point; anything toggled this session
        // has already been merged into `overrides` and stays on top.
        overrides: { ...(action.roadmap.collapseOverrides ?? {}), ...state.overrides },
      }
    case 'select':
      return {
        ...state, selectedId: action.id, check: null, error: null,
        reformulating: false, notice: null,
      }
    case 'close':
      return {
        ...state, selectedId: null, check: null, error: null,
        reformulating: false, notice: null,
      }
    case 'prefetch':
      return { ...state, prefetch: action.prefetch }
    case 'expand':
      return { ...state, expandingLegId: action.legId }
    case 'reformulate':
      return { ...state, reformulating: action.on }
    case 'notice':
      return { ...state, busy: false, notice: action.message, error: null }
    case 'dismissNotice':
      return { ...state, notice: null }
    case 'lens':
      return { ...state, lens: action.lens }
    case 'overrides':
      return { ...state, overrides: action.overrides }
    case 'busy':
      return { ...state, busy: action.busy, error: action.busy ? null : state.error }
    case 'error':
      return {
        ...state,
        busy: false,
        error: action.message,
        check: state.check ? { ...state.check, busy: false } : null,
      }
    case 'checkStarted':
      return {
        ...state,
        busy: false,
        check: { ...action.check, picked: null, answer: '', verdict: null, busy: false },
      }
    case 'checkPick':
      return { ...state, check: { ...state.check, picked: action.index } }
    case 'checkAnswer':
      return { ...state, check: { ...state.check, answer: action.value } }
    case 'checkBusy':
      return { ...state, check: { ...state.check, busy: action.busy } }
    case 'checkVerdict':
      return { ...state, check: { ...state.check, busy: false, verdict: action.verdict } }
    case 'checkClosed':
      return { ...state, check: null }
    default:
      return state
  }
}

// ── shaping the tree into a route ─────────────────────────────────────────────

// What a node *is*, not what it happens to contain. Inferring "module" from having children
// meant an undrafted module — a module with no steps yet — looked exactly like a leaf step, and
// got drawn as an ordinary stop on the route with a "Mark done" button on it. The type says so
// directly, so use it.
const isModule = (node) => node.type === 'roadmap'
const labelOf = (node) => node.content?.title || node.content?.text || 'Untitled'

/**
 * Flatten the tree into the ordered list of stations — leaf steps only, each tagged with the
 * leg (top-level module) it belongs to. Substeps flatten into the same line rather than
 * branching it: they're still work you do in sequence, and a branch would imply a choice.
 */
function toStations(children = []) {
  const stations = []
  const legs = []
  children.forEach((top) => {
    const legIndex = legs.length
    legs.push({
      id: top.id,
      node: top,
      title: labelOf(top),
      scope: top.content?.scope ?? null,
      // Rolled up server-side (RoadmapNodeResponse.NodeProgress) — done and verified are
      // separate numbers, so a collapsed leg can admit "8 done, 0 proved".
      progress: top.progress ?? null,
      missingProject: Boolean(top.missingProjectFlag),
      start: stations.length,
      count: 0,
    })
    // `parentId` is the node a step actually hangs off — its module, or the step above it when
    // it's a substep. Resource discovery works a parent at a time, so the map needs the real
    // one, not just which leg the step ended up in.
    // Anything with children recurses — a step broken into substeps flattens into the same line
    // rather than branching it, since substeps are still work done in sequence. An empty module
    // contributes no stations at all; that's what makes its leg unbuilt below.
    const walk = (node, parentId) => {
      if (node.children?.length) node.children.forEach((child) => walk(child, node.id))
      else if (!isModule(node)) stations.push({ node, legIndex, parentId })
    }
    if (isModule(top)) top.children.forEach((child) => walk(child, top.id))
    else stations.push({ node: top, legIndex, parentId: null })
    legs[legIndex].count = stations.length - legs[legIndex].start
  })
  // A module with no steps yet stays on the route, drawn as an unbuilt leg. It used to be
  // filtered out entirely, which meant a roadmap whose later modules hadn't been drafted showed
  // a shorter route than it really had — the map quietly under-reported the work. Better to show
  // the gap and let it be filled.
  return { stations, legs: legs.map((l) => ({ ...l, unbuilt: l.count === 0 })) }
}

function columnsFor(width) {
  if (width < 520) return 2
  if (width < 760) return 3
  if (width < 1040) return 4
  if (width < 1360) return 5
  return 6
}

/** Serpentine position: even rows run left→right, odd rows right→left. */
function layout(count, width) {
  const cols = columnsFor(width)
  const cellW = width / cols
  return {
    cols,
    rows: Math.max(1, Math.ceil(count / cols)),
    points: Array.from({ length: count }, (_, i) => {
      const row = Math.floor(i / cols)
      const slot = i % cols
      const col = row % 2 === 0 ? slot : cols - 1 - slot
      return { x: cellW * (col + 0.5), y: PAD_Y + ROW_H * row + ROW_H / 2 }
    }),
  }
}

/**
 * One segment of track between two stations. Same row is a straight run; a row change is the
 * U-turn at the end of a line, drawn as a rounded elbow so the route reads as continuous rather
 * than as two disconnected rows.
 */
function segmentPath(a, b) {
  if (a.y === b.y) return `M ${a.x} ${a.y} L ${b.x} ${b.y}`
  const midY = (a.y + b.y) / 2
  return `M ${a.x} ${a.y} C ${a.x} ${midY}, ${b.x} ${midY}, ${b.x} ${b.y}`
}

/**
 * A prerequisite link that the route itself doesn't already express. Consecutive stops are
 * joined by the track, so drawing an arc for those would just be a second line on top of the
 * first — only genuine jumps ("this needs something from four stops back") earn one.
 */
function depArc(a, b) {
  const dx = b.x - a.x
  const dy = b.y - a.y
  const dist = Math.hypot(dx, dy)
  const lift = Math.min(70, 24 + dist * 0.12)
  const mx = (a.x + b.x) / 2
  const my = (a.y + b.y) / 2 - lift
  return `M ${a.x} ${a.y} Q ${mx} ${my}, ${b.x} ${b.y}`
}

// ── component ─────────────────────────────────────────────────────────────────

export default function RoadmapMap({ id, onBack, onGone, onOpenClassic }) {
  const [state, dispatch] = useReducer(reducer, initialState)
  const {
    roadmap, selectedId, lens, overrides, check, busy, error,
    prefetch, expandingLegId, reformulating, notice,
  } = state
  const mapRef = useRef(null)
  const widthRef = useRef(0)
  const [, forceResize] = useReducer((n) => n + 1, 0)

  const load = useCallback(
    async (silent) => {
      try {
        const next = await getRoadmap(id)
        dispatch({ type: 'loaded', roadmap: next })
      } catch (err) {
        if (String(err.message).includes('404')) onGone?.()
        else if (!silent) dispatch({ type: 'error', message: err.message })
      }
    },
    [id, onGone],
  )

  useEffect(() => {
    load()
  }, [load])

  // Width drives the column count, so re-layout on resize. One observer for the whole map
  // rather than per-station measurement — positions are computed, never read back.
  useLayoutEffect(() => {
    const el = mapRef.current
    if (!el) return undefined
    const ro = new ResizeObserver(([entry]) => {
      const w = Math.round(entry.contentRect.width)
      if (w !== widthRef.current) {
        widthRef.current = w
        forceResize()
      }
    })
    ro.observe(el)
    return () => ro.disconnect()
  })

  const { stations, legs } = useMemo(
    () => (roadmap ? toStations(roadmap.children) : { stations: [], legs: [] }),
    [roadmap],
  )
  const width = widthRef.current || 900

  const currentStepIdEarly = roadmap?.progress?.currentStepId ?? null
  const currentLegIndex = useMemo(() => {
    const i = stations.findIndex((s) => s.node.id === currentStepIdEarly)
    return i < 0 ? 0 : stations[i].legIndex
  }, [stations, currentStepIdEarly])

  const open = useMemo(
    () => openLegs(legs, overrides, currentLegIndex),
    [legs, overrides, currentLegIndex],
  )
  const items = useMemo(() => toItems(stations, legs, open), [stations, legs, open])
  const { points, rows } = useMemo(() => layout(items.length, width), [items.length, width])

  // Stop id -> its position in the drawn route, for dependency arcs. A stop inside a collapsed
  // leg has no point, so arcs touching it are simply not drawn rather than aimed at nothing.
  const pointIndexByStopId = useMemo(() => {
    const m = new Map()
    items.forEach((it, i) => {
      if (it.kind === 'stop') m.set(it.station.node.id, i)
    })
    return m
  }, [items])

  async function toggleLeg(leg, legIndex) {
    const currentlyOpen = open.has(legIndex)
    const next = { ...overrides, [String(leg.id)]: currentlyOpen }
    dispatch({ type: 'overrides', overrides: next })
    try {
      // PATCH replaces the whole map rather than merging per key (PatchEntryRequest is explicit
      // about this), so the full current map goes every time. Sending just the toggled key wipes
      // every other leg's saved state. RB-4.10 semantics: true = collapsed, false = expanded.
      await patchEntry(id, { collapseOverrides: next })
    } catch {
      // A failed preference save isn't worth interrupting the session over; the local state
      // still applies and the next successful patch carries it.
    }
  }

  const hasUnbuilt = useMemo(() => legs.some((l) => l.unbuilt), [legs])

  // Unbuilt modules are already being drafted server-side from the moment they appear, so poll
  // for that rather than making the founder trigger a call that's probably already finished.
  // Polling stops as soon as nothing is unbuilt — no standing timer on a fully-drafted roadmap.
  useEffect(() => {
    if (!hasUnbuilt) return undefined
    let alive = true
    const tick = async () => {
      try {
        const list = await getModulePrefetchStatus(id)
        if (!alive) return
        dispatch({
          type: 'prefetch',
          prefetch: Object.fromEntries(list.map((s) => [String(s.moduleId), s])),
        })
      } catch {
        // The map is perfectly usable without this; an unreachable poll shouldn't say anything.
      }
    }
    tick()
    const timer = setInterval(tick, 5000)
    return () => {
      alive = false
      clearInterval(timer)
    }
  }, [id, hasUnbuilt])

  const selected = useMemo(
    () => stations.find((s) => s.node.id === selectedId) ?? null,
    [stations, selectedId],
  )
  const selectedNode = selected?.node ?? null
  const currentStepId = roadmap?.progress?.currentStepId ?? null
  const currentIndex = stations.findIndex((s) => s.node.id === currentStepId)

  const now = Date.now()
  // Only prerequisites the track doesn't already show — see depArc. Both ends must be drawn,
  // so a dependency into a collapsed leg is omitted rather than pointing at nothing.
  const arcs = useMemo(
    () =>
      items
        .map((it, i) => {
          if (it.kind !== 'stop') return null
          const from = pointIndexByStopId.get(it.station.node.dependsOn)
          return from == null || from === i - 1 ? null : { from, to: i }
        })
        .filter(Boolean),
    [items, pointIndexByStopId],
  )

  const counts = useMemo(() => {
    const c = { verified: 0, claimed: 0, due: 0, todo: 0 }
    stations.forEach((s) => {
      c[stopState(s.node, now)] = (c[stopState(s.node, now)] ?? 0) + 1
    })
    return c
  }, [stations, now])

  // Arrow keys walk the route; Enter opens the stop. Selection is the cursor, so this works
  // whether you arrived by click or by keyboard.
  const onMapKeyDown = useCallback(
    (e) => {
      if (!stations.length) return
      const i = stations.findIndex((s) => s.node.id === selectedId)
      const go = (to) => {
        if (to < 0 || to >= stations.length) return
        e.preventDefault()
        dispatch({ type: 'select', id: stations[to].node.id })
      }
      if (e.key === 'ArrowRight' || e.key === 'ArrowDown') go(i < 0 ? 0 : i + 1)
      else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') go(i < 0 ? 0 : i - 1)
      else if (e.key === 'Home') go(0)
      else if (e.key === 'End') go(stations.length - 1)
    },
    [stations, selectedId],
  )

  async function setStatus(status) {
    dispatch({ type: 'busy', busy: true })
    try {
      await patchEntry(selectedId, { status })
      await load(true)
      dispatch({ type: 'busy', busy: false })
    } catch (err) {
      dispatch({ type: 'error', message: err.message })
    }
  }

  async function startCheck() {
    dispatch({ type: 'busy', busy: true })
    try {
      const { format } = await getStepDefaultFormat(selectedId)
      dispatch({ type: 'checkStarted', check: await getStepCheck(selectedId, format) })
    } catch (err) {
      dispatch({ type: 'error', message: err.message })
    }
  }

  async function submitCheck() {
    dispatch({ type: 'checkBusy', busy: true })
    try {
      const verdict = await verifyStep(selectedId, check.answer?.trim() || null, check.picked)
      dispatch({ type: 'checkVerdict', verdict })
      if (verdict.passed) await load(true)
    } catch (err) {
      dispatch({ type: 'error', message: err.message })
    }
  }

  /**
   * Fill in resources for the steps under `parentId` that have none. Worth having as a deliberate
   * action rather than something automatic: resource discovery is grounded in a live search and
   * several AI calls, and the founder decides when a stretch of the map is worth spending that on.
   */
  async function findResources(parentId) {
    dispatch({ type: 'busy', busy: true })
    try {
      const { filled } = await backfillResources(id, parentId)
      await load(true)
      dispatch({
        type: 'notice',
        message: filled > 0
          ? `Found resources for ${filled} step${filled === 1 ? '' : 's'}.`
          : 'Nothing worth linking turned up for these.',
      })
    } catch (err) {
      dispatch({ type: 'error', message: err.message })
    }
  }

  if (!roadmap) {
    return (
      <div className="atlas rm-loading" role="status">
        {error || 'Charting…'}
      </div>
    )
  }

  const progress = roadmap.progress ?? { total: 0, done: 0 }
  const svgHeight = PAD_Y * 2 + ROW_H * rows

  return (
    <div className="atlas rm">
      <header className="rm-bar">
        <button type="button" className="a-btn a-btn--quiet" onClick={onBack}>
          ← Roadmaps
        </button>
        <div className="rm-bar__title">
          <h1>{roadmap.title}</h1>
          <p className="rm-bar__stat a-num">
            {currentIndex >= 0 ? `stop ${currentIndex + 1}` : 'not started'} of {stations.length}
            <span aria-hidden="true"> · </span>
            {legs.length} legs
            {roadmap.archetype && (
              <>
                <span aria-hidden="true"> · </span>
                {roadmap.archetype.replace(/_/g, ' ')}
              </>
            )}
            {progress.estimatedTotalMinutes > 0 && (
              <>
                <span aria-hidden="true"> · </span>
                {Math.round(progress.estimatedTotalMinutes / 60)}h est
              </>
            )}
            {progress.paceSessions > 0 && progress.paceMultiplier && (
              <>
                <span aria-hidden="true"> · </span>
                {progress.paceMultiplier < 1 ? 'ahead of' : 'behind'} estimate
              </>
            )}
            {/* Proved and merely-ticked are counted separately on purpose. */}
            {counts.verified > 0 && (
              <>
                <span aria-hidden="true"> · </span>
                {counts.verified} proved
              </>
            )}
            {counts.claimed + counts.due > 0 && (
              <>
                <span aria-hidden="true"> · </span>
                <span className="rm-bar__soft">{counts.claimed + counts.due} unproven</span>
              </>
            )}
          </p>
        </div>
        <div className="rm-bar__lenses" role="group" aria-label="Filter the map">
          {LENSES.map((l) => (
            <button
              key={l.id}
              type="button"
              className={'rm-lens' + (lens === l.id ? ' is-on' : '')}
              aria-pressed={lens === l.id}
              onClick={() => dispatch({ type: 'lens', lens: l.id })}
            >
              {l.label}
            </button>
          ))}
          {currentIndex >= 0 && (
            <button
              type="button"
              className="rm-lens"
              onClick={() => dispatch({ type: 'select', id: stations[currentIndex].node.id })}
            >
              Jump to now
            </button>
          )}
          <button type="button" className="rm-lens" onClick={() => downloadRoadmapExport(id)}>
            Export JSON
          </button>
          {/* The list view still owns the structural edits the map doesn't do — reordering,
              replanning what's left, re-tiering, inserting a module. It had no link from here
              at all, which made those reachable only by typing the URL. */}
          {onOpenClassic && (
            <button type="button" className="rm-lens" onClick={onOpenClassic}>
              Edit structure
            </button>
          )}
        </div>

        <div className="rm-bar__legs" aria-label="Legs">
          {legs.map((leg, i) => (
            <button
              key={leg.id}
              type="button"
              className="rm-legchip"
              style={{ '--leg': `var(--a-leg-${(i % 6) + 1})` }}
              onClick={() => dispatch({ type: 'select', id: stations[leg.start].node.id })}
            >
              <span className="rm-legchip__swatch" aria-hidden="true" />
              {leg.title}
            </button>
          ))}
        </div>
      </header>

      <div className="rm-scroll">
        <div
          className="rm-map"
          ref={mapRef}
          tabIndex={0}
          role="application"
          aria-label="Route map. Arrow keys move between stops."
          onKeyDown={onMapKeyDown}
        >
          <svg
            className="rm-svg"
            width="100%"
            height={svgHeight}
            viewBox={`0 0 ${width} ${svgHeight}`}
            role="img"
            aria-label={`Route map: ${progress.done} of ${stations.length} stops covered`}
          >
            {/* Prerequisite arcs, drawn only for the selected stop — what it needs, and what
                needs it. Rendering all 42 at once turned the map into a cat's cradle: a
                dependency web is only legible as an answer to "what about this one?", never as
                a permanent layer. */}
            {selectedId != null &&
              arcs
                .filter(
                  (a) =>
                    items[a.to].station?.node.id === selectedId ||
                    items[a.from].station?.node.id === selectedId,
                )
                .map((a) => (
                  <path
                    key={`arc-${a.from}-${a.to}`}
                    className="rm-arc is-lit"
                    d={depArc(points[a.from], points[a.to])}
                  />
                ))}

            {/* Track next, so stations sit on top of it. Each segment is its own path so the
                covered/ahead split is exact even when done steps aren't contiguous. */}
            {points.slice(0, -1).map((p, i) => {
              const it = items[i]
              const covered =
                it.kind === 'stop'
                  ? it.station.node.status === 'done'
                  : (it.leg.progress?.done ?? 0) >= (it.leg.progress?.total ?? 1)
              return (
                <path
                  key={`seg-${i}`}
                  d={segmentPath(p, points[i + 1])}
                  className={'rm-track' + (covered ? ' is-covered' : '')}
                  style={{ '--leg': `var(--a-leg-${(it.legIndex % 6) + 1})` }}
                />
              )
            })}

            {points.map((p, i) => {
              const it = items[i]
              const legVar = { '--leg': `var(--a-leg-${(it.legIndex % 6) + 1})` }

              if (it.kind === 'leg') {
                const pr = it.leg.progress ?? { total: it.leg.count, done: 0, verified: 0, due: 0 }
                const complete = pr.total > 0 && pr.done >= pr.total
                const unbuilt = it.leg.unbuilt
                const drafted = prefetch[String(it.leg.id)]?.status === 'DONE'
                const act = () =>
                  unbuilt ? dispatch({ type: 'expand', legId: it.leg.id }) : toggleLeg(it.leg, it.legIndex)
                return (
                  <g
                    key={`leg-${it.leg.id}`}
                    className={
                      'rm-bundle' +
                      (complete ? ' is-complete' : '') +
                      (unbuilt ? ' is-unbuilt' : '')
                    }
                    style={legVar}
                    transform={`translate(${p.x} ${p.y})`}
                    onClick={act}
                    role="button"
                    tabIndex={0}
                    aria-expanded={unbuilt ? undefined : false}
                    aria-label={
                      unbuilt
                        ? `${it.leg.title}, not drafted yet. ${drafted ? 'Review the draft.' : 'Draft it.'}`
                        : `${it.leg.title}, ${pr.done} of ${pr.total} done. Expand.`
                    }
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        act()
                      }
                    }}
                  >
                    {/* A collapsed leg is drawn as a stack, so it reads as "several stops in
                        here" rather than as one big stop. An unbuilt one is the same stack
                        outlined rather than filled — the shape of work that isn't there yet. */}
                    <rect className="rm-bundle__back" x={-26} y={-15} width={52} height={30} rx="8" />
                    <rect className="rm-bundle__mid" x={-23} y={-12} width={46} height={24} rx="7" />
                    <rect className="rm-bundle__front" x={-20} y={-9} width={40} height={18} rx="6" />
                    <text className="rm-bundle__count a-num" y={4}>
                      {unbuilt ? (drafted ? 'ready' : '—') : `${pr.done}/${pr.total}`}
                    </text>
                    {it.leg.missingProject && <circle className="rm-bundle__flag" cx={20} cy={-11} r="3.5" />}
                  </g>
                )
              }

              const { node } = it.station
              const state = stopState(node, now)
              const here = node.id === currentStepId
              const project = node.content?.kind === 'project'
              const r = radiusOf(node)
              const dim = !matchesLens(node, lens, state)
              const hasResources = (node.content?.resources?.length ?? 0) > 0
              return (
                <g
                  key={node.id}
                  className={
                    `rm-stop is-${state}` +
                    (here ? ' is-here' : '') +
                    (dim ? ' is-dim' : '') +
                    (node.id === selectedId ? ' is-selected' : '')
                  }
                  style={legVar}
                  transform={`translate(${p.x} ${p.y})`}
                  onClick={() => dispatch({ type: 'select', id: node.id })}
                >
                  {here && (
                    <>
                      <circle className="rm-stop__ring" r={r + 5} />
                      <circle className="rm-stop__halo" r={r + 5} />
                    </>
                  )}
                  {state === 'due' && <circle className="rm-stop__due" r={r + 4} />}
                  {project ? (
                    <rect className="rm-stop__mark" x={-r} y={-r} width={r * 2} height={r * 2} rx="3" />
                  ) : (
                    <circle className="rm-stop__mark" r={r} />
                  )}
                  {state === 'verified' && (
                    <path className="rm-stop__tick" d="M -4 0 L -1.2 3 L 4.2 -3.2" fill="none" />
                  )}
                  {state === 'claimed' && <circle className="rm-stop__core" r={r * 0.36} />}
                  {hasResources && <circle className="rm-stop__res" cx={r * 0.92} cy={-r * 0.92} r="2.4" />}
                  <text className="rm-stop__idx" y={r + 15}>
                    {it.index + 1}
                  </text>
                </g>
              )
            })}

          </svg>

          {/* Labels are real DOM, not SVG <text>: they wrap, they're selectable, and each one is
              the actual button you press — so keyboard order follows the route. */}
          {points.map((p, i) => {
            const it = items[i]
            if (it.kind === 'leg') {
              const pr = it.leg.progress
              const unbuilt = it.leg.unbuilt
              const job = prefetch[String(it.leg.id)]
              return (
                <button
                  key={`leglabel-${it.leg.id}`}
                  type="button"
                  className={'rm-label rm-label--leg' + (unbuilt ? ' is-unbuilt' : '')}
                  style={{ left: p.x, top: p.y + 24, width: width / columnsFor(width) - 20 }}
                  onClick={() =>
                    unbuilt
                      ? dispatch({ type: 'expand', legId: it.leg.id })
                      : toggleLeg(it.leg, it.legIndex)
                  }
                >
                  <span className="rm-label__legname">{it.leg.title}</span>
                  {unbuilt && (
                    <span className="rm-label__unbuilt">
                      {job?.status === 'DONE'
                        ? 'draft ready'
                        : job?.status === 'PENDING'
                          ? 'drafting…'
                          : 'no steps yet'}
                    </span>
                  )}
                  {/* Done and proved are shown apart, so a leg can't quietly claim mastery. */}
                  {pr && pr.verified < pr.done && (
                    <span className="rm-label__unproven a-num">{pr.done - pr.verified} unproven</span>
                  )}
                  {pr && pr.due > 0 && <span className="rm-label__due a-num">{pr.due} due</span>}
                </button>
              )
            }
            const { node } = it.station
            const dim = !matchesLens(node, lens, stopState(node, now))
            return (
              <button
                key={node.id}
                type="button"
                className={
                  'rm-label' +
                  (node.id === selectedId ? ' is-selected' : '') +
                  (dim ? ' is-dim' : '')
                }
                style={{ left: p.x, top: p.y + radiusOf(node) + 14, width: width / columnsFor(width) - 20 }}
                aria-current={node.id === currentStepId ? 'step' : undefined}
                onClick={() => dispatch({ type: 'select', id: node.id })}
              >
                {labelOf(node)}
              </button>
            )
          })}

          {/* Leg names sit on the route where the leg begins. */}
          {legs.map((leg, i) => {
            if (!open.has(i)) return null // a collapsed leg names itself on its bundle
            const p = points[items.findIndex((it) => it.kind === 'stop' && it.legIndex === i)]
            if (!p) return null
            return (
              <span
                key={`legname-${leg.id}`}
                className="rm-legname"
                style={{ left: p.x, top: p.y - R - 34, '--leg': `var(--a-leg-${(i % 6) + 1})` }}
              >
                {leg.title}
              </span>
            )
          })}
        </div>
      </div>

      {selectedNode && (
        <StopCard
          stop={selectedNode}
          isCurrent={selectedNode.id === currentStepId}
          index={stations.findIndex((s) => s.node.id === selectedNode.id) + 1}
          total={stations.length}
          canVerify={Boolean(selectedNode.content?.verify || roadmap.verify)}
          check={check}
          busy={busy}
          error={error}
          notice={notice}
          reformulating={reformulating}
          canFindResources={selected?.parentId != null}
          onClose={() => dispatch({ type: 'close' })}
          onStatus={setStatus}
          onStartCheck={startCheck}
          onFindResources={() => findResources(selected.parentId)}
          onReformulate={(on) => dispatch({ type: 'reformulate', on })}
          onReformulated={async () => {
            dispatch({ type: 'reformulate', on: false })
            await load(true)
          }}
          onDismissNotice={() => dispatch({ type: 'dismissNotice' })}
          onPick={(index) => dispatch({ type: 'checkPick', index })}
          onAnswer={(value) => dispatch({ type: 'checkAnswer', value })}
          onSubmit={submitCheck}
          onCloseCheck={() => dispatch({ type: 'checkClosed' })}
        />
      )}

      {/* Drafting a module reuses the same propose→approve→apply editor as everything else —
          the founder edits the steps before any of them are kept. */}
      {expandingLegId != null && (
        <ExpandModuleModal
          roadmapId={id}
          module={legs.find((l) => l.id === expandingLegId).node}
          prefetched={
            prefetch[String(expandingLegId)]?.status === 'DONE'
              ? prefetch[String(expandingLegId)].result
              : null
          }
          onClose={() => dispatch({ type: 'expand', legId: null })}
          onApplied={async () => {
            dispatch({ type: 'expand', legId: null })
            await load(true)
          }}
        />
      )}
    </div>
  )
}

/**
 * The station card — docked over the map, never replacing it. Escape closes; focus moves in on
 * open and back to the map on close, so keyboard use doesn't get stranded behind the overlay.
 */
function StopCard({
  stop, isCurrent, index, total, canVerify, check, busy, error, notice, reformulating,
  canFindResources, onClose, onStatus, onStartCheck, onFindResources, onReformulate,
  onReformulated, onDismissNotice, onPick, onAnswer, onSubmit, onCloseCheck,
}) {
  const ref = useRef(null)

  useEffect(() => {
    ref.current?.focus()
    const onKey = (e) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const done = stop.status === 'done'
  const resources = stop.content?.resources ?? []
  const covers = stop.content?.covers ?? []

  return (
    <aside className="rm-card" aria-label="Stop detail" tabIndex={-1} ref={ref}>
      <div className="rm-card__head">
        <p className="a-eyebrow a-num">
          Stop {index} / {total}
        </p>
        <button type="button" className="a-btn a-btn--quiet rm-card__x" onClick={onClose} aria-label="Close">
          ✕
        </button>
      </div>

      <h2 className="rm-card__title">{stop.content?.text || 'Untitled'}</h2>

      <div className="rm-card__tags">
        {/* "Marked done" and "proved" are different claims and are labelled as such. */}
        {done && stop.content?.verifiedAt && <span className="a-tag a-tag--done">proved</span>}
        {done && !stop.content?.verifiedAt && (
          <span className="a-tag a-tag--attention">marked done · unproven</span>
        )}
        {isCurrent && !done && <span className="a-tag a-tag--attention">you're here</span>}
        {stop.content?.kind && <span className="a-tag">{stop.content.kind}</span>}
        {stop.content?.weight && <span className="a-tag">{stop.content.weight}</span>}
      </div>

      <div className="rm-card__body">
        {stop.content?.rationale && <p className="rm-card__why">{stop.content.rationale}</p>}

        {covers.length > 0 && (
          <section>
            <p className="a-eyebrow">Covers</p>
            <ul className="rm-card__covers">
              {covers.map((c) => (
                <li key={c}>
                  <span className="a-tag">{c}</span>
                </li>
              ))}
            </ul>
          </section>
        )}

        <section>
          <p className="a-eyebrow">Resources</p>
          {resources.length > 0 ? (
            <ul className="rm-card__res">
              {resources.map((r) => (
                <li key={r.url || r.title}>
                  <a href={r.url} target="_blank" rel="noreferrer noopener">
                    {r.title} <span aria-hidden="true">↗</span>
                  </a>
                  {r.format && <span className="a-tag rm-card__resfmt">{r.format}</span>}
                </li>
              ))}
            </ul>
          ) : (
            <p className="rm-card__empty">
              None yet.
              {canFindResources && (
                <>
                  {' '}
                  <button type="button" className="rm-card__link" onClick={onFindResources} disabled={busy}>
                    {busy ? 'Looking…' : 'Find some'}
                  </button>{' '}
                  — covers the rest of this leg too.
                </>
              )}
            </p>
          )}
          {notice && (
            <p className="rm-card__notice" role="status" onAnimationEnd={onDismissNotice}>
              {notice}
            </p>
          )}
        </section>

        {check && (
          <CheckBlock
            check={check}
            onPick={onPick}
            onAnswer={onAnswer}
            onSubmit={onSubmit}
            onClose={onCloseCheck}
            onRetry={onStartCheck}
          />
        )}

        {/* Reformulation stays user-initiated, never offered unprompted (CLAUDE.md Section 8). */}
        {reformulating && (
          <ReformulatePanel
            step={stop}
            onClose={() => onReformulate(false)}
            onApplied={onReformulated}
          />
        )}
      </div>

      <div className="rm-card__foot">
        {error && (
          <span className="rm-card__err" role="alert">
            {error}
          </span>
        )}
        <span className="rm-card__spacer" />
        {!done && !check && !reformulating && (
          <button type="button" className="a-btn a-btn--quiet" onClick={() => onReformulate(true)}>
            Too much?
          </button>
        )}
        {!done && canVerify && !check && (
          <button type="button" className="a-btn" onClick={onStartCheck} disabled={busy}>
            {busy ? 'Writing…' : 'Check me'}
          </button>
        )}
        <button
          type="button"
          className={'a-btn' + (done ? '' : ' a-btn--primary')}
          onClick={() => onStatus(done ? 'captured' : 'done')}
          disabled={busy}
        >
          {done ? 'Reopen' : 'Mark done'}
        </button>
      </div>
    </aside>
  )
}

function CheckBlock({ check, onPick, onAnswer, onSubmit, onClose, onRetry }) {
  const mc = check.format === 'multiple_choice'
  const answered = Boolean(check.verdict)
  const canSubmit = mc ? check.picked != null : check.answer.trim().length > 0

  return (
    <section className="rm-check">
      <p className="a-eyebrow">{check.format.replace('_', ' ')}</p>
      <p className="rm-check__q">{check.question}</p>

      {mc ? (
        <div className="rm-check__opts">
          {check.options.map((option, i) => (
            <button
              key={option}
              type="button"
              className={'rm-opt' + (check.picked === i ? ' is-picked' : '')}
              aria-pressed={check.picked === i}
              disabled={answered || check.busy}
              onClick={() => onPick(i)}
            >
              <span className="rm-opt__key" aria-hidden="true">{String.fromCharCode(65 + i)}</span>
              <span>{option}</span>
            </button>
          ))}
        </div>
      ) : (
        <textarea
          className="rm-check__answer"
          rows={6}
          value={check.answer}
          disabled={answered || check.busy}
          onChange={(e) => onAnswer(e.target.value)}
          aria-label="Your answer"
        />
      )}

      <div aria-live="polite">
        {check.verdict && (
          <div className={'rm-verdict ' + (check.verdict.passed ? 'is-pass' : 'is-fail')}>
            <p>{check.verdict.passed ? 'That holds up. Marked done.' : check.verdict.gap}</p>
            {check.verdict.suggestedPrerequisite && (
              <p className="rm-verdict__prereq">
                Might be worth first: {check.verdict.suggestedPrerequisite}
              </p>
            )}
          </div>
        )}
      </div>

      <div className="rm-check__foot">
        <button type="button" className="a-btn a-btn--quiet" onClick={onClose}>
          {answered ? 'Close' : 'Cancel'}
        </button>
        {answered && !check.verdict.passed && (
          <button type="button" className="a-btn" onClick={onRetry}>
            Try another
          </button>
        )}
        {!answered && (
          <button
            type="button"
            className="a-btn a-btn--primary"
            onClick={onSubmit}
            disabled={!canSubmit || check.busy}
          >
            {check.busy ? 'Checking…' : 'Submit'}
          </button>
        )}
      </div>
    </section>
  )
}
