import React, { useState, useEffect } from 'react';
import { useAppStore } from '../stores/appStore';
import { SidebarWidget } from './SidebarWidget';
import { usePluginHost } from '../plugins/PluginHost';
import type { Note } from '@shared/types';
import clsx from 'clsx';

interface SidebarProps {
  position: 'left' | 'right';
}

export const Sidebar: React.FC<SidebarProps> = ({ position }) => {
  const allNotes = useAppStore(state => state.allNotes);
  const currentNote = useAppStore(state => state.currentNote);
  const setCurrentNote = useAppStore(state => state.setCurrentNote);
  const { getSidebarWidgets, registry } = usePluginHost();
  const [widgetComponents, setWidgetComponents] = useState<React.ReactNode[]>([]);
  
  useEffect(() => {
    const widgets = getSidebarWidgets();
    const components = widgets.map(widget => (
      <SidebarWidget
        key={widget.id}
        id={widget.id}
        title={widget.title}
        icon={widget.icon}
        defaultOpen={widget.defaultOpen}
      >
        <widget.component />
      </SidebarWidget>
    ));
    setWidgetComponents(components);
  }, [registry.sidebarWidgets.length]);
  
  if (position === 'left') {
    return (
      <div className="sidebar sidebar-left">
        <SidebarWidget id="file-explorer" title="文件资源" icon="📁" defaultOpen={true}>
          <FileExplorer notes={allNotes} currentNote={currentNote} onSelect={setCurrentNote} />
        </SidebarWidget>
        {widgetComponents.filter((_, i) => i % 2 === 0)}
      </div>
    );
  }
  
  return (
    <div className="sidebar sidebar-right">
      {widgetComponents.filter((_, i) => i % 2 === 1)}
      <SidebarWidget id="outgoing-links" title="出链" icon="➡️" defaultOpen={false}>
        <OutgoingLinks note={currentNote} onSelect={setCurrentNote} allNotes={allNotes} />
      </SidebarWidget>
    </div>
  );
};

const FileExplorer: React.FC<{
  notes: Note[];
  currentNote: Note | null;
  onSelect: (note: Note) => void;
}> = ({ notes, currentNote, onSelect }) => {
  const [groupedNotes, setGroupedNotes] = useState<Record<string, Note[]>>({});
  
  useEffect(() => {
    const grouped: Record<string, Note[]> = {};
    for (const note of notes) {
      const dir = note.path.split('/').slice(0, -1).join('/') || '根目录';
      if (!grouped[dir]) {
        grouped[dir] = [];
      }
      grouped[dir].push(note);
    }
    setGroupedNotes(grouped);
  }, [notes]);
  
  return (
    <div className="file-explorer">
      {Object.entries(groupedNotes).map(([dir, dirNotes]) => (
        <div key={dir} className="file-group">
          <div className="file-group-title">📂 {dir}</div>
          <div className="file-list">
            {dirNotes.map(note => (
              <div
                key={note.id}
                className={clsx('file-item', { active: currentNote?.id === note.id })}
                onClick={() => onSelect(note)}
              >
                <span className="file-icon">📄</span>
                <span className="file-name">{note.title}</span>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
};

const OutgoingLinks: React.FC<{
  note: Note | null;
  allNotes: Note[];
  onSelect: (note: Note) => void;
}> = ({ note, allNotes, onSelect }) => {
  const [links, setLinks] = useState<any[]>([]);
  
  useEffect(() => {
    if (note) {
      window.api.links.getForwardLinks(note.id).then(setLinks);
    } else {
      setLinks([]);
    }
  }, [note?.id]);
  
  if (!note) {
    return (
      <div className="plugin-empty">
        <p>选择一篇笔记查看出链</p>
      </div>
    );
  }
  
  if (links.length === 0) {
    return (
      <div className="plugin-empty">
        <p>暂无出链</p>
      </div>
    );
  }
  
  return (
    <div className="outgoing-links">
      <div className="outgoing-links-count">{links.length} 条出链</div>
      <div className="outgoing-links-list">
        {links.map(link => {
          const targetNote = allNotes.find(n => n.id === link.targetId);
          return (
            <div
              key={link.id}
              className="outgoing-link-item"
              onClick={() => targetNote && onSelect(targetNote)}
            >
              {targetNote?.title || link.targetPath}
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default Sidebar;
