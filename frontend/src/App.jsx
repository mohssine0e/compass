import CaptureScreen from './components/CaptureScreen'
import './App.css'

export default function App() {
  return (
    <div className="app">
      <header className="app-header">
        <span className="wordmark">Compass</span>
      </header>

      <main className="app-main">
        <CaptureScreen />
      </main>
    </div>
  )
}
