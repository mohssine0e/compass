import { describe, expect, it } from 'vitest'
import {
  dependencyInfo,
  findNode,
  findNodeDepth,
  findNodePath,
  formatMinutes,
  fullyDoneGroups,
  hasEmptyModule,
  nodeIndexOf,
  seedCollapsed,
} from '../../roadmapTree'

// A small nested tree: one fully-done module, one in-progress module (containing the current
// step), and one still-empty (unexpanded) module.
function sampleTree() {
  return [
    {
      id: 1,
      type: 'roadmap',
      content: { title: 'Module A (done)' },
      progress: { total: 2, done: 2 },
      children: [
        { id: 11, type: 'roadmap_step', content: { text: 'A1' }, status: 'done' },
        { id: 12, type: 'roadmap_step', content: { text: 'A2' }, status: 'done' },
      ],
    },
    {
      id: 2,
      type: 'roadmap',
      content: { title: 'Module B (in progress)' },
      progress: { total: 2, done: 1 },
      children: [
        { id: 21, type: 'roadmap_step', content: { text: 'B1' }, status: 'done' },
        { id: 22, type: 'roadmap_step', content: { text: 'B2' }, status: 'captured', dependsOn: 21 },
      ],
    },
    {
      id: 3,
      type: 'roadmap',
      content: { title: 'Module C (not drafted yet)' },
      progress: { total: 0, done: 0 },
      children: [],
    },
  ]
}

describe('fullyDoneGroups', () => {
  it('collects only container ids whose progress is total>0 and done===total', () => {
    expect(fullyDoneGroups(sampleTree())).toEqual([1])
  })

  it('recurses into nested containers', () => {
    const nested = [
      {
        id: 1,
        children: [
          { id: 2, progress: { total: 1, done: 1 }, children: [{ id: 3, content: {} }] },
        ],
      },
    ]
    expect(fullyDoneGroups(nested)).toEqual([2])
  })

  it('an empty tree yields no groups', () => {
    expect(fullyDoneGroups([])).toEqual([])
  })
})

describe('hasEmptyModule', () => {
  it('true when any module in the tree has no children yet', () => {
    expect(hasEmptyModule(sampleTree())).toBe(true)
  })

  it('false once every module has steps', () => {
    const fullyExpanded = sampleTree().slice(0, 2)
    expect(hasEmptyModule(fullyExpanded)).toBe(false)
  })

  it('false for a flat roadmap (leaf steps, no module nodes)', () => {
    const flat = [
      { id: 1, type: 'roadmap_step', content: { text: 'step 1' } },
      { id: 2, type: 'roadmap_step', content: { text: 'step 2' } },
    ]
    expect(hasEmptyModule(flat)).toBe(false)
  })
})

describe('nodeIndexOf', () => {
  it('maps every node id to itself and its direct parent id', () => {
    const map = nodeIndexOf(sampleTree())
    expect(map.get(11).parentId).toBe(1)
    expect(map.get(22).parentId).toBe(2)
    expect(map.get(1).parentId).toBeNull()
  })
})

describe('dependencyInfo', () => {
  it('null when the node has no dependsOn', () => {
    const map = nodeIndexOf(sampleTree())
    expect(dependencyInfo({ id: 11, dependsOn: null }, map)).toBeNull()
  })

  it('reports done and same-module for an in-module dependency', () => {
    const tree = sampleTree()
    const map = nodeIndexOf(tree)
    const b2 = tree[1].children[1]
    const info = dependencyInfo(b2, map)
    expect(info).toEqual({ text: 'B1', done: true, crossModule: false })
  })

  it('flags a cross-module dependency as crossModule, never a gate', () => {
    const tree = sampleTree()
    const map = nodeIndexOf(tree)
    const info = dependencyInfo({ id: 22, dependsOn: 11 }, map)
    expect(info.crossModule).toBe(true)
  })

  it('null when the dependency target is not in the tree', () => {
    const map = nodeIndexOf(sampleTree())
    expect(dependencyInfo({ id: 22, dependsOn: 999 }, map)).toBeNull()
  })
})

describe('findNode / findNodePath / findNodeDepth', () => {
  it('findNode locates a deeply nested node by id', () => {
    expect(findNode(sampleTree(), 22)?.content.text).toBe('B2')
  })

  it('findNode returns null for an id not in the tree', () => {
    expect(findNode(sampleTree(), 999)).toBeNull()
  })

  it('findNodePath returns the ancestor chain, excluding the target itself', () => {
    const path = findNodePath(sampleTree(), 22)
    expect(path.map((n) => n.id)).toEqual([2])
  })

  it('findNodePath is null when the target is not in the tree', () => {
    expect(findNodePath(sampleTree(), 999)).toBeNull()
  })

  it('findNodeDepth: top-level module is depth 0, its step is depth 1', () => {
    expect(findNodeDepth(sampleTree(), 2)).toBe(0)
    expect(findNodeDepth(sampleTree(), 22)).toBe(1)
  })
})

describe('formatMinutes', () => {
  it('renders sub-hour durations as minutes only', () => {
    expect(formatMinutes(45)).toBe('45 min')
  })

  it('renders exact hours without a minutes remainder', () => {
    expect(formatMinutes(120)).toBe('2h')
  })

  it('renders a mixed duration as both', () => {
    expect(formatMinutes(90)).toBe('1h 30 min')
  })

  it('zero renders as 0 min, not blank', () => {
    expect(formatMinutes(0)).toBe('0 min')
  })
})

describe('seedCollapsed', () => {
  it('a flat roadmap (shape !== nested) collapses nothing', () => {
    expect(seedCollapsed({ shape: 'flat', children: sampleTree() })).toEqual(new Set())
  })

  it('a TOPIC-tier roadmap collapses only fully-done modules', () => {
    const result = seedCollapsed({ shape: 'nested', tier: 'TOPIC', children: sampleTree() })
    expect(result).toEqual(new Set([1]))
  })

  it('a CAREER-tier roadmap collapses every module except the one holding the current step', () => {
    const data = {
      shape: 'nested',
      tier: 'CAREER',
      children: sampleTree(),
      progress: { currentStepId: 22 },
    }
    // Module 2 holds step 22 (current) and stays open; module 1 collapses. Module 3 has no
    // children yet, so it was never a collapsible "group" in the first place — nothing to
    // collapse on an empty module.
    expect(seedCollapsed(data)).toEqual(new Set([1]))
  })

  it("the founder's manual collapseOverrides always win over the tier default", () => {
    const data = {
      shape: 'nested',
      tier: 'CAREER',
      children: sampleTree(),
      progress: { currentStepId: 22 },
      collapseOverrides: { 2: true, 1: false },
    }
    expect(seedCollapsed(data)).toEqual(new Set([2]))
  })
})
