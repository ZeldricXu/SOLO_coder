import React, { useState, useMemo } from 'react';
import type { PluginDefinition, SidebarWidget, Note } from '@shared/types';
import { useAppStore } from '../stores/appStore';

const TagsWidget: React.FC = () => {
  const allNotes = useAppStore(state => state.allNotes);
  const [selectedTag, setSelectedTag] = useState<string | null>(null);
  const setCurrentNote = useAppStore(state => state.setCurrentNote);
  
  const tagStats = useMemo(() => {
    const tagMap = new Map<string, { count: number; notes: Note[] }>();
    
    for (const note of allNotes) {
      for (const tag of note.tags) {
        if (!tagMap.has(tag)) {
          tagMap.set(tag, { count: 0, notes: [] });
        }
        const tagData = tagMap.get(tag)!;
        tagData.count++;
        tagData.notes.push(note);
      }
    }
    
    return Array.from(tagMap.entries())
      .map(([name, data]) => ({ name, ...data }))
      .sort((a, b) => b.count - a.count);
  }, [allNotes]);
  
  const selectedTagNotes = useMemo(() => {
    if (!selectedTag) return [];
    return allNotes.filter(note => note.tags.includes(selectedTag));
  }, [selectedTag, allNotes]);
  
  if (tagStats.length === 0) {
    return (
      <div className="plugin-empty">
        <p>暂无标签</p>
      </div>
    );
  }
  
  return (
    <div className="tags-widget">
      {selectedTag ? (
        <>
          <div className="tags-header">
            <button className="tags-back" onClick={() => setSelectedTag(null)}>
              ← 返回
            </button>
            <span className="tags-selected">#{selectedTag}</span>
          </div>
          <div className="tag-notes-list">
            {selectedTagNotes.map(note => (
              <div
                key={note.id}
                className="tag-note-item"
                onClick={() => setCurrentNote(note)}
              >
                {note.title}
              </div>
            ))}
          </div>
        </>
      ) : (
        <div className="tags-list">
          {tagStats.map(tag => (
            <div
              key={tag.name}
              className="tag-item"
              onClick={() => setSelectedTag(tag.name)}
            >
              <span className="tag-name">#{tag.name}</span>
              <span className="tag-count">{tag.count}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

const tagsPlugin: PluginDefinition = {
  id: 'tags',
  name: '标签管理',
  version: '1.0.0',
  description: '管理和浏览笔记标签',
  activate: (context) => {
    const widget: SidebarWidget = {
      id: 'tags-widget',
      title: '标签',
      icon: '🏷️',
      component: TagsWidget as React.ComponentType,
      defaultOpen: false,
    };
    context.registerSidebarWidget(widget);
  },
};

export default tagsPlugin;
