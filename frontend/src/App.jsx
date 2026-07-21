import { useEffect, useState } from 'react'
import CaptureScreen from './components/CaptureScreen'
import NewRoadmapScreen from './components/NewRoadmapScreen'
import GenerateRoadmapScreen from './components/GenerateRoadmapScreen'
import RoadmapsScreen from './components/RoadmapsScreen'
import RoadmapDetail from './components/RoadmapDetail'
import AllEntriesScreen from './components/AllEntriesScreen'
import AdminEventsScreen from './components/AdminEventsScreen'
import ProfileScreen from './components/ProfileScreen'
import FocusScreen from './components/FocusScreen'
import ReviewScreen from './components/ReviewScreen'
import ResurfacingScreen from './components/ResurfacingScreen'
import { getNextResurfacing } from './api'
import './App.css'

// Lightweight view state — no router dependency (keeping to the approved stack).
export default function App() {
  // Start in a brief check so a stalled thing can surface *before* the capture screen.
  const [view, setView] = useState({ name: 'loading' })

  const go = (name, params = {}) => setView({ name, ...params })

  useEffect(() => {
    let alive = true
    getNextResurfacing()
      .then((prompt) => {
        if (!alive) return
        setView(prompt ? { name: 'resurfacing', prompt } : { name: 'capture' })
      })
      .catch(() => alive && setView({ name: 'capture' }))
    return () => {
      alive = false
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
        </nav>
      </header>

      <main className="app-main">
        {view.name === 'resurfacing' && (
          <ResurfacingScreen prompt={view.prompt} onDone={() => go('capture')} />
        )}
        {view.name === 'capture' && <CaptureScreen />}
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
            onCreated={(id) => go('roadmap', { id })}
            onManual={() => go('newRoadmap')}
            onCancel={() => go('roadmaps')}
          />
        )}
        {view.name === 'roadmap' && (
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
