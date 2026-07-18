import { useState } from 'react'
import CaptureScreen from './components/CaptureScreen'
import NewRoadmapScreen from './components/NewRoadmapScreen'
import RoadmapsScreen from './components/RoadmapsScreen'
import RoadmapDetail from './components/RoadmapDetail'
import './App.css'

// Lightweight view state — no router dependency (keeping to the approved stack).
export default function App() {
  const [view, setView] = useState({ name: 'capture' })

  const go = (name, params = {}) => setView({ name, ...params })

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
            active={view.name.startsWith('roadmap') || view.name === 'newRoadmap'}
            onClick={() => go('roadmaps')}
          >
            Roadmaps
          </NavLink>
        </nav>
      </header>

      <main className="app-main">
        {view.name === 'capture' && <CaptureScreen />}
        {view.name === 'roadmaps' && (
          <RoadmapsScreen
            onNew={() => go('newRoadmap')}
            onOpen={(id) => go('roadmap', { id })}
          />
        )}
        {view.name === 'newRoadmap' && (
          <NewRoadmapScreen
            onCreated={(id) => go('roadmap', { id })}
            onCancel={() => go('roadmaps')}
          />
        )}
        {view.name === 'roadmap' && (
          <RoadmapDetail id={view.id} onBack={() => go('roadmaps')} />
        )}
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
