import React, { useState } from 'react';
import { AppProvider } from './context/AppContext';
import { SubtitleRenderer } from './components/SubtitleRenderer';
import { ControlPanel } from './components/ControlPanel';
import { ExportPanel } from './components/ExportPanel';
import { HistoryPanel } from './components/HistoryPanel';
import './styles/App.css';

type View = 'recording' | 'history';

function AppContent() {
  const [currentView, setCurrentView] = useState<View>('recording');

  return (
    <div className="app">
      <header className="app-header">
        <h1 className="app-title">🎤 VoiceTrans</h1>
        <nav className="app-nav">
          <button
            className={`nav-btn ${currentView === 'recording' ? 'active' : ''}`}
            onClick={() => setCurrentView('recording')}
          >
            实时转写
          </button>
          <button
            className={`nav-btn ${currentView === 'history' ? 'active' : ''}`}
            onClick={() => setCurrentView('history')}
          >
            历史记录
          </button>
        </nav>
      </header>

      <main className="app-main">
        {currentView === 'recording' ? (
          <div className="recording-view">
            <ControlPanel />
            <SubtitleRenderer />
            <ExportPanel />
          </div>
        ) : (
          <HistoryPanel />
        )}
      </main>
    </div>
  );
}

function App() {
  return (
    <AppProvider>
      <AppContent />
    </AppProvider>
  );
}

export default App;
