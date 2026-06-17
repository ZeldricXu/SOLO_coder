import React, { useState, useEffect } from 'react';
import type { PluginDefinition, SidebarWidget, NoteLink, Note } from '@shared/types';
import { useAppStore } from '../stores/appStore';
import './pluginStyles.css';

const BacklinksWidget: React.FC = () => {
  const currentNote = useAppStore(state => state.currentNote);
  const [backlinks, setBacklinks] = useState<NoteLink[]>([]);
  const [sourceNotes, setSourceNotes] = useState<Map<string, Note>>(new Map());
  const setCurrentNote = useAppStore(state => state.setCurrentNote);
  const allNotes = useAppStore(state => state.allNotes);
  
  useEffect(() => {
    if (currentNote) {
      window.api.links.getBacklinks(currentNote.id).then(links => {
        setBacklinks(links);
      });
    } else {
      setBacklinks([]);
    }
  }, [currentNote?.id]);
  
  useEffect(() => {
    const noteMap = new Map<string, Note>();
    for (const note of allNotes) {
      noteMap.set(note.id, note);
    }
    setSourceNotes(noteMap);
  }, [allNotes]);
  
  if (!currentNote) {
    return (
      <div className="plugin-empty">
        <p>选择一篇笔记查看反向链接</p>
      </div>
    );
  }
  
  if (backlinks.length === 0) {
    return (
      <div className="plugin-empty">
        <p>暂无反向链接</p>
      </div>
    );
  }
  
  return (
    <div className="backlinks-widget">
      <div className="backlinks-count">{backlinks.length} 条反向链接</div>
      <div className="backlinks-list">
        {backlinks.map(link => {
          const sourceNote = sourceNotes.get(link.sourceId);
          return (
            <div
              key={link.id}
              className="backlink-item"
              onClick={() => sourceNote && setCurrentNote(sourceNote)}
            >
              <div className="backlink-title">
                {sourceNote?.title || link.sourcePath}
              </div>
              <div className="backlink-context">
                {link.context}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

const backlinksPlugin: PluginDefinition = {
  id: 'backlinks',
  name: '反向链接',
  version: '1.0.0',
  description: '显示指向当前笔记的反向链接',
  activate: (context) => {
    const widget: SidebarWidget = {
      id: 'backlinks-widget',
      title: '反向链接',
      icon: '🔗',
      component: BacklinksWidget as React.ComponentType,
      defaultOpen: true,
    };
    context.registerSidebarWidget(widget);
  },
};

export default backlinksPlugin;
