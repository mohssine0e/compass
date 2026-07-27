import { useState } from 'react'
import { classifyGoal, runClassifyTestSet } from '../api'
import { Section, Button, Badge, TextArea } from './ui'
import './ClassifyTestScreen.css'

// RB-1.2: a throwaway debug screen for the Roadmap Brain tier classifier — not part of the
// real app's Capture/Draft-with-AI flow, no auth (single-user, CLAUDE.md). Calls
// /admin/classify-test directly, never RoadmapService.generate.
export default function ClassifyTestScreen() {
  const [goal, setGoal] = useState('')
  const [single, setSingle] = useState(null)
  const [singleBusy, setSingleBusy] = useState(false)
  const [singleError, setSingleError] = useState(null)

  const [rows, setRows] = useState(null)
  const [allBusy, setAllBusy] = useState(false)
  const [allError, setAllError] = useState(null)

  async function handleClassify() {
    if (!goal.trim() || singleBusy) return
    setSingleBusy(true)
    setSingleError(null)
    try {
      setSingle(await classifyGoal(goal.trim()))
    } catch (err) {
      setSingleError(err.message)
      setSingle(null)
    } finally {
      setSingleBusy(false)
    }
  }

  async function handleRunAll() {
    if (allBusy) return
    setAllBusy(true)
    setAllError(null)
    try {
      setRows(await runClassifyTestSet())
    } catch (err) {
      setAllError(err.message)
    } finally {
      setAllBusy(false)
    }
  }

  const passCount = rows ? rows.filter((r) => r.pass === true).length : 0
  const failCount = rows ? rows.filter((r) => r.pass === false).length : 0
  const judgedCount = rows ? rows.filter((r) => r.pass !== null).length : 0

  return (
    <div className="classify-test">
      <h1 className="screen-title">Tier classifier — RB-1</h1>

      <Section title="Classify one goal" hint="Never shown to the founder — internal routing signal only.">
        <TextArea
          value={goal}
          onChange={(e) => setGoal(e.target.value)}
          rows={2}
          placeholder="Type any goal…"
        />
        <div className="classify-test-actions">
          <Button variant="primary" onClick={handleClassify} disabled={singleBusy || !goal.trim()}>
            {singleBusy ? 'Classifying…' : 'Classify'}
          </Button>
        </div>
        {singleError && <p className="classify-test-error">{singleError}</p>}
        {single && (
          <div className="classify-test-result">
            <Badge tone="brass">{single.tier}</Badge>
            <span className="classify-test-confidence">
              confidence {(single.confidence * 100).toFixed(0)}%
            </span>
            <p className="classify-test-reasoning">{single.reasoning}</p>
          </div>
        )}
      </Section>

      <Section
        title="Run all 26 test goals"
        hint="The exact validation set from TASKS_v2.md RB-1.3 — re-run after every prompt change."
      >
        <div className="classify-test-actions">
          <Button onClick={handleRunAll} disabled={allBusy}>
            {allBusy ? 'Running…' : 'Run all 26 test goals'}
          </Button>
          {rows && (
            <span className="classify-test-summary">
              {passCount}/{judgedCount} clear-cut cases pass
              {failCount > 0 ? `, ${failCount} fail` : ''}
            </span>
          )}
        </div>
        {allError && <p className="classify-test-error">{allError}</p>}
        {rows && (
          <div className="classify-test-table-wrap">
            <table className="classify-test-table">
              <thead>
                <tr>
                  <th>Goal</th>
                  <th>Expected</th>
                  <th>Actual</th>
                  <th>Confidence</th>
                  <th>Reasoning</th>
                  <th>Pass</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={i} className={'classify-test-row' + (r.pass === false ? ' is-fail' : '')}>
                    <td>{r.goal}</td>
                    <td>{r.expectedTier || '—'}</td>
                    <td>{r.actualTier}</td>
                    <td>{(r.confidence * 100).toFixed(0)}%</td>
                    <td className="classify-test-row-reasoning">{r.reasoning}</td>
                    <td>
                      {r.pass === null ? (
                        '—'
                      ) : (
                        <Badge tone={r.pass ? 'default' : 'danger'}>
                          {r.pass ? 'pass' : 'fail'}
                        </Badge>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>
    </div>
  )
}
