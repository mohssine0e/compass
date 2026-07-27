import { useEffect, useState } from 'react'
import UnifiedIntakeScreen from './components/UnifiedIntakeScreen'
import NewRoadmapScreen from './components/NewRoadmapScreen'
import GenerateRoadmapScreen from './components/GenerateRoadmapScreen'
import RoadmapsScreen from './components/RoadmapsScreen'
import RoadmapDetail from './components/RoadmapDetail'
import RoadmapMap from './components/RoadmapMap'
import AllEntriesScreen from './components/AllEntriesScreen'
import AdminEventsScreen from './components/AdminEventsScreen'
import ClassifyTestScreen from './components/ClassifyTestScreen'
import ProfileScreen from './components/ProfileScreen'
import FocusScreen from './components/FocusScreen'
import ReviewScreen from './components/ReviewScreen'
import ResurfacingScreen from './components/ResurfacingScreen'
import ErrorBoundary from './components/ErrorBoundary'
import { getNextResurfacing } from './api'
import './App.css'

// View state lives in the URL (V3-5.1). Still no router dependency — the History API is enough
// for a flat set of screens, and keeping the stack lean was the right call. What wasn't right was
// the cost: with view state held only in useState, the browser/Android back gesture had nothing
// to pop, so on the installed PWA it exited the app instead of going back a screen. Encoding the
// view in the path also makes a roadmap linkable and survives a reload in place.
const PATHS = {
  capture: '/',
  roadmaps: '/roadmaps',
  newRoadmap: '/roadmaps/new',
  generateRoadmap: '/roadmaps/draft',
  focus: '/focus',
  review: '/review',
  all: '/all',
  profile: '/profile',
  events: '/events',
  classifyTest: '/classify-test',
}

// Views that take the whole viewport instead of the centred reading column.
const FULL_BLEED = new Set(['roadmap'])

function viewToPath(view) {
  if (view.name === 'roadmap') return `/roadmap/${view.id}`
  if (view.name === 'roadmapClassic') return `/roadmap/${view.id}/classic`
  return PATHS[view.name] || '/'
}

// Only views that are meaningful to land on directly are parsed back. `resurfacing` and
// `generateRoadmap` carry in-memory payloads (a prompt, a draft result) that a cold URL can't
// reconstruct, so they fall back to their own starting point rather than rendering half-empty.
function pathToView(pathname) {
  const classic = pathname.match(/^\/roadmap\/(\d+)\/classic$/)
  if (classic) return { name: 'roadmapClassic', id: Number(classic[1]) }
  const roadmap = pathname.match(/^\/roadmap\/(\d+)$/)
  if (roadmap) return { name: 'roadmap', id: Number(roadmap[1]) }
  const name = Object.keys(PATHS).find((k) => PATHS[k] === pathname)
  if (name === 'generateRoadmap') return { name: 'roadmaps' }
  return name ? { name } : { name: 'capture' }
}

export default function App() {
  // Start in a brief check so a stalled thing can surface *before* the capture screen.
  const [view, setView] = useState({ name: 'loading' })

  const go = (name, params = {}) => {
    const next = { name, ...params }
    const path = viewToPath(next)
    if (path !== window.location.pathname) {
      window.history.pushState(null, '', path)
    }
    setView(next)
  }

  useEffect(() => {
    let alive = true
    const landing = pathToView(window.location.pathname)

    // An explicit deep link wins over the resurfacing check — being sent somewhere else after
    // deliberately opening a roadmap would read as the app losing your place.
    if (landing.name !== 'capture') {
      setView(landing)
    } else {
      getNextResurfacing()
        .then((prompt) => {
          if (!alive) return
          setView(prompt ? { name: 'resurfacing', prompt } : { name: 'capture' })
        })
        .catch(() => alive && setView({ name: 'capture' }))
    }

    const onPop = () => setView(pathToView(window.location.pathname))
    window.addEventListener('popstate', onPop)
    return () => {
      alive = false
      window.removeEventListener('popstate', onPop)
    }
  }, [])

  return (
    <div className="app">
      <header className="app-header">
        <button className="wordmark" onClick={() => go('capture')}>
          Compass
        </button>
        <nav className="app-nav">
          <NavLink active={view.name === 'capture'} onClick={() => go('capture')}>
            Capture
          </NavLink>
          <NavLink
            active={
              view.name.startsWith('roadmap') ||
              view.name === 'newRoadmap' ||
              view.name === 'generateRoadmap'
            }
            onClick={() => go('roadmaps')}
          >
            Roadmaps
          </NavLink>
          <NavLink active={view.name === 'focus'} onClick={() => go('focus')}>
            Focus
          </NavLink>
          <NavLink active={view.name === 'review'} onClick={() => go('review')}>
            Review
          </NavLink>
          <NavLink active={view.name === 'all'} onClick={() => go('all')}>
            All
          </NavLink>
          <NavLink active={view.name === 'profile'} onClick={() => go('profile')}>
            Profile
          </NavLink>
          <NavLink active={view.name === 'events'} onClick={() => go('events')}>
            Events
          </NavLink>
          <NavLink active={view.name === 'classifyTest'} onClick={() => go('classifyTest')}>
            Classify test
          </NavLink>
        </nav>
      </header>

      <main className={'app-main' + (FULL_BLEED.has(view.name) ? ' app-main--full' : '')}>
        {/* Keyed on the view so a crash in one screen doesn't leave the boundary stuck showing
            the error after navigating somewhere else. */}
        <ErrorBoundary key={view.name} onReset={() => go('capture')}>
        {view.name === 'resurfacing' && (
          <ResurfacingScreen prompt={view.prompt} onDone={() => go('capture')} />
        )}
        {view.name === 'capture' && (
          <UnifiedIntakeScreen
            onOpenRoadmap={(id) => go('roadmap', { id })}
            onOpenRoadmapBuilder={(goal, initialResult) => go('generateRoadmap', { goal, initialResult })}
            onOpenAll={() => go('all')}
          />
        )}
        {view.name === 'roadmaps' && (
          <RoadmapsScreen
            onNew={() => go('newRoadmap')}
            onDraft={() => go('generateRoadmap')}
            onOpen={(id) => go('roadmap', { id })}
          />
        )}
        {view.name === 'newRoadmap' && (
          <NewRoadmapScreen
            onCreated={(id) => go('roadmap', { id })}
            onCancel={() => go('roadmaps')}
          />
        )}
        {view.name === 'generateRoadmap' && (
          <GenerateRoadmapScreen
            initialGoal={view.goal}
            initialResult={view.initialResult}
            onCreated={(id) => go('roadmap', { id })}
            onManual={() => go('newRoadmap')}
            onCancel={() => go('roadmaps')}
          />
        )}
        {view.name === 'roadmap' && (
          <RoadmapMap
            id={view.id}
            onBack={() => go('roadmaps')}
            onGone={() => go('roadmaps')}
            onOpenClassic={() => go('roadmapClassic', { id: view.id })}
          />
        )}
        {/* The list view, reached from the map's "Edit structure". The map covers reading the
            roadmap, drafting a module, verifying, reformulating and finding resources; this
            still owns the structural edits — reordering, replanning, re-tiering, inserting. */}
        {view.name === 'roadmapClassic' && (
          <RoadmapDetail
            id={view.id}
            onBack={() => go('roadmaps')}
            onGone={() => go('roadmaps')}
          />
        )}
        {view.name === 'focus' && (
          <FocusScreen
            onOpenResurfacing={(prompt) => go('resurfacing', { prompt })}
            onOpenRoadmap={(id) => go('roadmap', { id })}
          />
        )}
        {view.name === 'review' && <ReviewScreen />}
        {view.name === 'all' && (
          <AllEntriesScreen
            onOpenRoadmap={(id) => go('roadmap', { id })}
            onDraftFromTheme={(goal) => go('generateRoadmap', { goal })}
          />
        )}
        {view.name === 'profile' && <ProfileScreen />}
        {view.name === 'events' && <AdminEventsScreen />}
        {view.name === 'classifyTest' && <ClassifyTestScreen />}
        </ErrorBoundary>
      </main>
    </div>
  )
}

function NavLink({ active, onClick, children }) {
  return (
    <button
      className={'nav-link' + (active ? ' is-active' : '')}
      onClick={onClick}
    >
      {children}
    </button>
  )
}
