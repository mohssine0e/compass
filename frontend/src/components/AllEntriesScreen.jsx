import { useCallback, useEffect, useState } from 'react'
import { clusterIdeas, listEntries, patchEntry } from '../api'
import IdeaDetailModal from './IdeaDetailModal'
import { Badge, Button } from './ui'
import './AllEntries.css'

// Plain visibility over everything captured (Phase 1), reshaped to stop forcing a scroll
// (Phase 14): grouped and collapsible instead of one flat list, with an optional AI-clustered
// theme view built on top of the same propose→approve pattern used everywhere else in the app.
const STATUS_GROUPS = [
  { key: 'in_motion', label: 'In motion', collapsedByDefault: false },
  { key: 'developing', label: 'Developing', collapsedByDefault: false },
  { key: 'captured', label: 'Captured', collapsedByDefault: false },
  { key: 'done', label: 'Done', collapsedByDefault: true },
  { key: 'dropped', label: 'Dropped', collapsedByDefault: true },
  { key: 'archived', label: 'Archived', collapsedByDefault: true },
]

export default function AllEntriesScreen({ onOpenRoadmap, onDraftFromTheme }) {
  const [entries, setEntries] = useState(null)
  const [error, setError] = useState(null)
  const [groupBy, setGroupBy] = useState('status') // status | theme
  const [collapsed, setCollapsed] = useState(() => new Set(
    STATUS_GROUPS.filter((g) => g.collapsedByDefault).map((g) => g.key)
  ))
  const [openIdeaId, setOpenIdeaId] = useState(null)
  const [proposedClusters, setProposedClusters] = useState(null) // null | []
  const [clustering, setClustering] = useState(false)
  const [clusterError, setClusterError] = useState(null)

  const load = useCallback(async () => {
    try {
      setEntries(await listEntries())
    } catch (err) {
      setError(err.message)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  function toggleGroup(key) {
    setCollapsed((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  async function setIdeaStatus(id, status) {
    setError(null)
    try {
      await patchEntry(id, { status })
      await load()
    } catch (err) {
      setError(err.message)
    }
  }

  async function toggleTask(task) {
    setError(null)
    try {
      await patchEntry(task.id, { status: task.status === 'done' ? 'captured' : 'done' })
      await load()
    } catch (err) {
      setError(err.message)
    }
  }

  async function runCluster() {
    setClustering(true)
    setClusterError(null)
    try {
      const clusters = await clusterIdeas()
      setProposedClusters(clusters.map((c) => ({ ...c, label: c.label })))
    } catch (err) {
      setClusterError(err.message)
    } finally {
      setClustering(false)
    }
  }

  async function confirmCluster(index) {
    const cluster = proposedClusters[index]
    setError(null)
    try {
      for (const id of cluster.ideaIds) {
        const idea = all.find((e) => e.id === id)
        if (!idea) continue
        await patchEntry(id, { content: { ...idea.content, theme: cluster.label.trim() } })
      }
      setProposedClusters((prev) => prev.filter((_, i) => i !== index))
      await load()
    } catch (err) {
      setError(err.message)
    }
  }

  function dismissCluster(index) {
    setProposedClusters((prev) => prev.filter((_, i) => i !== index))
  }

  function setClusterLabel(index, label) {
    setProposedClusters((prev) => prev.map((c, i) => (i === index ? { ...c, label } : c)))
  }

  const all = entries || []
  const topLevel = all.filter((e) => e.parentId == null)
  const tasksByParent = new Map()
  for (const e of all) {
    if (e.type === 'task' && e.parentId != null) {
      if (!tasksByParent.has(e.parentId)) tasksByParent.set(e.parentId, [])
      tasksByParent.get(e.parentId).push(e)
    }
  }

  const openIdea = openIdeaId != null ? all.find((e) => e.id === openIdeaId) : null

  return (
    <div className="all-list">
      <div className="all-head">
        <h1 className="screen-title">Everything</h1>
        <div className="all-head-actions">
          <Button variant={groupBy === 'status' ? 'primary' : 'ghost'} onClick={() => setGroupBy('status')}>
            Status
          </Button>
          <Button variant={groupBy === 'theme' ? 'primary' : 'ghost'} onClick={() => setGroupBy('theme')}>
            Theme
          </Button>
        </div>
      </div>

      {error && <p className="all-error">{error}</p>}
      {entries && topLevel.length === 0 && <p className="all-empty">Nothing captured yet.</p>}

      {groupBy === 'status'
        ? STATUS_GROUPS.map((g) => {
            const items = topLevel.filter((e) => (e.status || '').toLowerCase() === g.key)
            if (items.length === 0) return null
            return (
              <EntryGroup
                key={g.key}
                label={g.label}
                count={items.length}
                open={!collapsed.has(g.key)}
                onToggle={() => toggleGroup(g.key)}
                items={items}
                tasksByParent={tasksByParent}
                onOpenRoadmap={onOpenRoadmap}
                onOpenIdea={setOpenIdeaId}
                onSetIdeaStatus={setIdeaStatus}
                onToggleTask={toggleTask}
              />
            )
          })
        : (() => {
            const ideas = topLevel.filter((e) => e.type === 'idea' && e.status !== 'dropped')
            const themed = new Map()
            const untitled = []
            for (const idea of ideas) {
              const theme = idea.content?.theme
              if (theme) {
                if (!themed.has(theme)) themed.set(theme, [])
                themed.get(theme).push(idea)
              } else {
                untitled.push(idea)
              }
            }
            const nonIdeas = topLevel.filter((e) => e.type !== 'idea')
            return (
              <>
                {nonIdeas.length > 0 && (
                  <EntryGroup
                    label="Roadmaps"
                    count={nonIdeas.length}
                    open={!collapsed.has('__roadmaps')}
                    onToggle={() => toggleGroup('__roadmaps')}
                    items={nonIdeas}
                    tasksByParent={tasksByParent}
                    onOpenRoadmap={onOpenRoadmap}
                    onOpenIdea={setOpenIdeaId}
                    onSetIdeaStatus={setIdeaStatus}
                    onToggleTask={toggleTask}
                  />
                )}
                {[...themed.entries()].map(([theme, items]) => (
                  <EntryGroup
                    key={theme}
                    label={theme}
                    count={items.length}
                    open={!collapsed.has(theme)}
                    onToggle={() => toggleGroup(theme)}
                    items={items}
                    tasksByParent={tasksByParent}
                    onOpenRoadmap={onOpenRoadmap}
                    onOpenIdea={setOpenIdeaId}
                    onSetIdeaStatus={setIdeaStatus}
                    onToggleTask={toggleTask}
                    onDraft={
                      onDraftFromTheme
                        ? () => onDraftFromTheme(
                            `Turn these into a plan: ${items.map((i) => i.content.text).join('; ')}`
                          )
                        : undefined
                    }
                  />
                ))}
                {untitled.length > 0 && (
                  <EntryGroup
                    label="Uncategorized"
                    count={untitled.length}
                    open={!collapsed.has('__uncategorized')}
                    onToggle={() => toggleGroup('__uncategorized')}
                    items={untitled}
                    tasksByParent={tasksByParent}
                    onOpenRoadmap={onOpenRoadmap}
                    onOpenIdea={setOpenIdeaId}
                    onSetIdeaStatus={setIdeaStatus}
                    onToggleTask={toggleTask}
                  >
                    {untitled.length >= 2 && !proposedClusters && (
                      <Button variant="ghost" onClick={runCluster} disabled={clustering}>
                        {clustering ? 'Looking for themes…' : 'Cluster with AI'}
                      </Button>
                    )}
                  </EntryGroup>
                )}
                {clusterError && <p className="all-error">{clusterError}</p>}
                {proposedClusters && proposedClusters.length === 0 && (
                  <p className="all-empty">Nothing clear enough to group yet.</p>
                )}
                {proposedClusters && proposedClusters.length > 0 && (
                  <div className="cluster-proposals">
                    {proposedClusters.map((c, i) => (
                      <div className="cluster-proposal" key={i}>
                        <input
                          className="step-input cluster-label-input"
                          value={c.label}
                          onChange={(e) => setClusterLabel(i, e.target.value)}
                        />
                        <ul className="cluster-members">
                          {c.ideaIds.map((id) => {
                            const idea = all.find((e) => e.id === id)
                            return idea ? <li key={id}>{idea.content.text}</li> : null
                          })}
                        </ul>
                        <div className="cluster-proposal-actions">
                          <Button variant="ghost" onClick={() => dismissCluster(i)}>
                            Dismiss
                          </Button>
                          <Button variant="primary" onClick={() => confirmCluster(i)} disabled={!c.label.trim()}>
                            Confirm
                          </Button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </>
            )
          })()}

      {openIdea && (
        <IdeaDetailModal
          idea={openIdea}
          tasks={tasksByParent.get(openIdea.id) || []}
          onClose={() => setOpenIdeaId(null)}
          onChanged={load}
          onToggleTask={toggleTask}
        />
      )}
    </div>
  )
}

function EntryGroup({ label, count, open, onToggle, items, tasksByParent, onOpenRoadmap,
  onOpenIdea, onSetIdeaStatus, onToggleTask, onDraft, children }) {
  return (
    <section className="all-group">
      <div className="all-group-head" onClick={onToggle}>
        <span className="all-group-caret" aria-hidden="true">{open ? '▾' : '▸'}</span>
        <span className="all-group-label">{label}</span>
        <Badge>{count}</Badge>
        {onDraft && (
          <Button
            variant="ghost"
            onClick={(e) => {
              e.stopPropagation()
              onDraft()
            }}
          >
            Draft a roadmap
          </Button>
        )}
        {children && <span onClick={(e) => e.stopPropagation()}>{children}</span>}
      </div>
      {open && (
        <ul className="all-items">
          {items.map((e) => {
            const isRoadmap = e.type === 'roadmap'
            const isIdea = e.type === 'idea'
            const text = isRoadmap ? e.content.title : e.content.text
            const tasks = tasksByParent.get(e.id) || []
            return (
              <li key={e.id}>
                <div className={'all-row' + (isRoadmap || isIdea ? ' is-clickable' : '')}>
                  <span
                    className="all-text"
                    onClick={isRoadmap ? () => onOpenRoadmap(e.id) : isIdea ? () => onOpenIdea(e.id) : undefined}
                    role={isRoadmap || isIdea ? 'button' : undefined}
                  >
                    {text}
                  </span>
                  <span className="all-tags">
                    <span className="all-tag">{isRoadmap ? 'roadmap' : e.type}</span>
                    {e.significance && <span className="all-tag">{e.significance}</span>}
                    {isIdea ? (
                      <select
                        className={`all-status-select status-${e.status}`}
                        value={e.status}
                        onChange={(ev) => onSetIdeaStatus(e.id, ev.target.value)}
                      >
                        {['captured', 'developing', 'in_motion', 'done', 'dropped'].map((s) => (
                          <option key={s} value={s}>
                            {s.replace('_', ' ')}
                          </option>
                        ))}
                      </select>
                    ) : (
                      <span className={`all-status status-${e.status}`}>
                        {e.status.replace('_', ' ')}
                      </span>
                    )}
                  </span>
                </div>

                {tasks.length > 0 && (
                  <ul className="all-tasks">
                    {tasks.map((t) => (
                      <li key={t.id} className={'all-task' + (t.status === 'done' ? ' is-done' : '')}>
                        <button
                          className="all-task-check"
                          onClick={() => onToggleTask(t)}
                          aria-label={t.status === 'done' ? 'Reopen task' : 'Mark task done'}
                        >
                          {t.status === 'done' ? '✓' : '○'}
                        </button>
                        <span className="all-task-text">{t.content.text}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
