import React, { useEffect, useState } from 'react';
import { useAppStore } from './stores/appStore';
import { PluginHostProvider, usePluginHost } from './plugins/PluginHost';
import { NoteEditor } from './components/NoteEditor';
import { Sidebar } from './components/Sidebar';
import { KnowledgeGraph } from './components/KnowledgeGraph';
import { CommandPalette } from './plugins/commandPalette';
import backlinksPlugin from './plugins/backlinks';
import tagsPlugin from './plugins/tags';
import commandPalettePlugin from './plugins/commandPalette';
import type { Note, GraphData, AppSettings } from '@shared/types';
import clsx from 'clsx';
import './styles/app.css';

const AppContent: React.FC = () => {
  const {
    currentNote,
    allNotes,
    settings,
    showCommandPalette,
    setCurrentNote,
    setAllNotes,
    setSettings,
    setShowCommandPalette,
  } = useAppStore();
  
  const { activatePlugin, getCommands, registry } = usePluginHost();
  const [graphData, setGraphData] = useState<GraphData>({ nodes: [], edges: [] });
  const [showGraph, setShowGraph] = useState(true);
  const [isLoading, setIsLoading] = useState(true);
  
  useEffect(() => {
    const initApp = async () => {
      try {
        const settings = await window.api.settings.get();
        setSettings(settings);
        
        const notes = await window.api.notes.getAll();
        setAllNotes(notes);
        
        const graph = await window.api.graph.getGraphData();
        setGraphData(graph);
        
        if (notes.length > 0) {
          setCurrentNote(notes[0]);
        }
        
        activatePlugin(backlinksPlugin);
        activatePlugin(tagsPlugin);
        activatePlugin(commandPalettePlugin);
        
        setIsLoading(false);
      } catch (err) {
        console.error('Failed to initialize app:', err);
        setIsLoading(false);
      }
    };
    
    initApp();
  }, []);
  
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'p') {
        e.preventDefault();
        setShowCommandPalette(!showCommandPalette);
      }
      if ((e.metaKey || e.ctrlKey) && e.key === 'g') {
        e.preventDefault();
        setShowGraph(prev => !prev);
      }
    };
    
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [showCommandPalette, setShowCommandPalette]);
  
  const handleSaveNote = async (content: string) => {
    if (currentNote) {
      await window.api.notes.saveContent(currentNote.id, content);
      const notes = await window.api.notes.getAll();
      setAllNotes(notes);
      const graph = await window.api.graph.getGraphData();
      setGraphData(graph);
    }
  };
  
  const handleLinkClick = async (target: string) => {
    const targetPath = target.endsWith('.md') ? target : target + '.md';
    const found = allNotes.find(n =>
      n.path === targetPath ||
      n.title === target ||
      n.path.endsWith(targetPath)
    );
    
    if (found) {
      setCurrentNote(found);
    } else {
      const newNote = await window.api.notes.create({
        title: target,
        path: targetPath,
        content: `# ${target}\n\n`,
        tags: [],
      });
      
      const notes = await window.api.notes.getAll();
      setAllNotes(notes);
      setCurrentNote(newNote);
      
      const graph = await window.api.graph.getGraphData();
      setGraphData(graph);
    }
  };
  
  const handleNodeClick = (node: any) => {
    const note = allNotes.find(n => n.id === node.id);
    if (note) {
      setCurrentNote(note);
    }
  };
  
  const openVaultSettings = async () => {
    const dirPath = await window.api.dialog.openDirectory({
      title: '选择 Vault 目录',
    });
    
    if (dirPath) {
      await window.api.vault.setPath(dirPath);
      const notes = await window.api.notes.getAll();
      setAllNotes(notes);
      const graph = await window.api.graph.getGraphData();
      setGraphData(graph);
      
      if (notes.length > 0) {
        setCurrentNote(notes[0]);
      }
    }
  };
  
  if (isLoading) {
    return (
      <div className="app-loading">
        <div className="loading-spinner"></div>
        <p>正在加载...</p>
      </div>
    );
  }
  
  return (
    <div className="app-container">
      <header className="app-header">
        <div className="app-logo">
          <span className="logo-icon">🧠</span>
          <span className="logo-text">智能笔记</span>
        </div>
        <div className="app-actions">
          <button
            className="header-btn"
            onClick={() => setShowGraph(!showGraph)}
            title="切换图谱 (⌘G)"
          >
            🕸️ 图谱
          </button>
          <button
            className="header-btn"
            onClick={openVaultSettings}
            title="设置 Vault 目录"
          >
            📁 打开 Vault
          </button>
          <button
            className="header-btn"
            onClick={() => setShowCommandPalette(true)}
            title="命令面板 (⌘P)"
          >
            ⌘ 命令
          </button>
        </div>
      </header>
      
      <div className="app-body">
        <Sidebar position="left" />
        
        <main className="main-content">
          <div className={clsx('editor-section', { 'with-graph': showGraph })}>
            <NoteEditor
              note={currentNote}
              onSave={handleSaveNote}
              onLinkClick={handleLinkClick}
            />
          </div>
          
          {showGraph && (
            <aside className="graph-section">
              <KnowledgeGraph
                data={graphData}
                currentNoteId={currentNote?.id}
                onNodeClick={handleNodeClick}
                height={500}
              />
            </aside>
          )}
        </main>
        
        <Sidebar position="right" />
      </div>
      
      <CommandPalette
        isOpen={showCommandPalette}
        onClose={() => setShowCommandPalette(false)}
        commands={getCommands()}
        notes={allNotes}
      />
    </div>
  );
};

const App: React.FC = () => {
  return (
    <PluginHostProvider>
      <AppContent />
    </PluginHostProvider>
  );
};

export default App;
