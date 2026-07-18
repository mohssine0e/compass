import { useState } from 'react'
import CaptureScreen from './components/CaptureScreen'
import NewRoadmapScreen from './components/NewRoadmapScreen'
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
            active={view.name === 'newRoadmap'}
            onClick={() => go('newRoadmap')}
          >
            New roadmap
          </NavLink>
        </nav>
      </header>

      <main className="app-main">
        {view.name === 'capture' && <CaptureScreen />}
        {view.name === 'newRoadmap' && (
          <NewRoadmapScreen onDone={() => go('capture')} />
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
