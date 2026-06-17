import React, { useState, useEffect, useRef, useMemo } from 'react';
import type { PluginDefinition, PluginCommand, Note, SearchResult } from '@shared/types';
import { useAppStore } from '../stores/appStore';

interface CommandPaletteProps {
  isOpen: boolean;
  onClose: () => void;
  commands: PluginCommand[];
  notes: Note[];
}

const CommandPalette: React.FC<CommandPaletteProps> = ({ isOpen, onClose, commands, notes }) => {
  const [query, setQuery] = useState('');
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const inputRef = useRef<HTMLInputElement>(null);
  const setCurrentNote = useAppStore(state => state.setCurrentNote);
  const setShowCommandPalette = useAppStore(state => state.setShowCommandPalette);
  
  useEffect(() => {
    if (isOpen) {
      setQuery('');
      setSelectedIndex(0);
      setTimeout(() => inputRef.current?.focus(), 50);
    }
  }, [isOpen]);
  
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (!isOpen) return;
      
      if (e.key === 'Escape') {
        onClose();
      } else if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSelectedIndex(prev => Math.min(prev + 1, getFilteredItems().length - 1));
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSelectedIndex(prev => Math.max(prev - 1, 0));
      } else if (e.key === 'Enter') {
        e.preventDefault();
        handleSelect(selectedIndex);
      }
    };
    
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, selectedIndex, query, searchResults, commands, notes]);
  
  useEffect(() => {
    if (query.startsWith('>') || query === '') {
      setSearchResults([]);
      return;
    }
    
    const timeout = setTimeout(async () => {
      if (query.trim()) {
        const results = await window.api.search.query(query, { limit: 10, highlight: true });
        setSearchResults(results);
      }
    }, 150);
    
    return () => clearTimeout(timeout);
  }, [query]);
  
  const filteredCommands = useMemo(() => {
    if (!query.startsWith('>')) return [];
    const searchQuery = query.slice(1).toLowerCase();
    return commands.filter(cmd =>
      cmd.label.toLowerCase().includes(searchQuery)
    );
  }, [query, commands]);
  
  const getFilteredItems = (): Array<{ type: 'command' | 'note'; data: any }> => {
    const items: Array<{ type: 'command' | 'note'; data: any }> = [];
    
    if (query.startsWith('>')) {
      items.push(...filteredCommands.map(cmd => ({ type: 'command' as const, data: cmd })));
    } else {
      items.push(...searchResults.map(r => ({ type: 'note' as const, data: r })));
    }
    
    return items;
  };
  
  const handleSelect = (index: number) => {
    const items = getFilteredItems();
    if (index < 0 || index >= items.length) return;
    
    const item = items[index];
    
    if (item.type === 'command') {
      item.data.execute();
      onClose();
    } else if (item.type === 'note') {
      const note = notes.find(n => n.id === item.data.id);
      if (note) {
        setCurrentNote(note);
      }
      onClose();
    }
  };
  
  if (!isOpen) return null;
  
  const items = getFilteredItems();
  
  return (
    <div className="command-palette-overlay" onClick={onClose}>
      <div className="command-palette" onClick={e => e.stopPropagation()}>
        <div className="command-palette-input-wrapper">
          <span className="command-palette-icon">⌘</span>
          <input
            ref={inputRef}
            type="text"
            value={query}
            onChange={e => {
              setQuery(e.target.value);
              setSelectedIndex(0);
            }}
            placeholder="搜索笔记或输入 > 执行命令"
            className="command-palette-input"
          />
        </div>
        
        <div className="command-palette-results">
          {items.length === 0 ? (
            <div className="command-palette-empty">
              {query.startsWith('>') ? '无匹配命令' : '输入关键词搜索笔记'}
            </div>
          ) : (
            items.map((item, index) => (
              <div
                key={index}
                className={`command-palette-item ${index === selectedIndex ? 'selected' : ''}`}
                onClick={() => handleSelect(index)}
                onMouseEnter={() => setSelectedIndex(index)}
              >
                {item.type === 'command' ? (
                  <>
                    <span className="command-icon">⌘</span>
                    <span className="command-label">{item.data.label}</span>
                    {item.data.shortcut && (
                      <span className="command-shortcut">{item.data.shortcut}</span>
                    )}
                  </>
                ) : (
                  <>
                    <span className="note-icon">📄</span>
                    <div className="note-info">
                      <span className="note-title" dangerouslySetInnerHTML={{
                        __html: item.data.highlight?.title || item.data.title
                      }} />
                      {item.data.highlight?.content && (
                        <span className="note-snippet" dangerouslySetInnerHTML={{
                          __html: item.data.highlight.content
                        }} />
                      )}
                    </div>
                  </>
                )}
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};

const commandPalettePlugin: PluginDefinition = {
  id: 'command-palette',
  name: '命令面板',
  version: '1.0.0',
  description: '全局命令面板，支持搜索和快捷操作',
  activate: (context) => {
    const toggleCommand = {
      id: 'toggle-command-palette',
      label: '切换命令面板',
      shortcut: '⌘P',
      execute: () => {
        const state = context.store.getState();
        state.setShowCommandPalette(!state.showCommandPalette);
      },
    };
    
    const quickNoteCommand = {
      id: 'quick-note',
      label: '新建快速笔记',
      shortcut: '⌘N',
      execute: async () => {
        const today = new Date().toISOString().split('T')[0];
        const note = await window.api.notes.create({
          title: `快速笔记 ${today}`,
          path: `quick-notes/${today}-${Date.now()}.md`,
          content: `# 快速笔记 ${today}\n\n`,
          tags: ['quick-note'],
        });
        const state = context.store.getState();
        state.setCurrentNote(note);
      },
    };
    
    const rescanCommand = {
      id: 'rescan-vault',
      label: '重新扫描 vault',
      execute: async () => {
        await window.api.vault.rescan();
      },
    };
    
    context.registerCommand(toggleCommand);
    context.registerCommand(quickNoteCommand);
    context.registerCommand(rescanCommand);
  },
};

export { CommandPalette, commandPalettePlugin };
export default commandPalettePlugin;
