import React, { useState, useCallback } from 'react';
import { format } from 'date-fns';
import { Note } from '../../shared/types';
import { zhCN } from 'date-fns/locale';

interface NoteListProps {
  notes: Note[];
  currentNoteId: string | null;
  isSearching: boolean;
  searchQuery: string;
  onSelectNote: (noteId: string) => void;
  onDeleteNote: (noteId: string) => void;
  onSearch: (query: string) => void;
  onCreateNote: () => void;
}

export const NoteList: React.FC<NoteListProps> = ({
  notes,
  currentNoteId,
  isSearching,
  searchQuery,
  onSelectNote,
  onDeleteNote,
  onSearch,
  onCreateNote,
}) => {
  const [contextMenu, setContextMenu] = useState<{
    x: number;
    y: number;
    noteId: string;
  } | null>(null);

  const handleContextMenu = useCallback((e: React.MouseEvent, noteId: string) => {
    e.preventDefault();
    e.stopPropagation();
    setContextMenu({ x: e.clientX, y: e.clientY, noteId });
  }, []);

  const handleDelete = useCallback(() => {
    if (contextMenu) {
      onDeleteNote(contextMenu.noteId);
      setContextMenu(null);
    }
  }, [contextMenu, onDeleteNote]);

  React.useEffect(() => {
    const handleClick = () => setContextMenu(null);
    document.addEventListener('click', handleClick);
    return () => document.removeEventListener('click', handleClick);
  }, []);

  const formatDate = (dateStr: string) => {
    try {
      return format(new Date(dateStr), 'MM-dd HH:mm', { locale: zhCN });
    } catch {
      return dateStr;
    }
  };

  const stripMarkdown = (text: string) => {
    return text
      .replace(/^#{1,6}\s+/gm, '')
      .replace(/\*\*([^*]+)\*\*/g, '$1')
      .replace(/\*([^*]+)\*/g, '$1')
      .replace(/`([^`]+)`/g, '$1')
      .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
      .replace(/\n+/g, ' ')
      .trim();
  };

  return (
    <div className="note-list">
      <div className="note-list-header">
        <input
          type="text"
          className="search-input"
          placeholder="搜索笔记..."
          value={searchQuery}
          onChange={(e) => onSearch(e.target.value)}
        />
        <button className="icon-button" onClick={onCreateNote} title="新建笔记">
          ✏️
        </button>
      </div>

      <div className="note-list-items">
        {notes.length === 0 ? (
          <div className="empty-state">
            <div className="empty-state-icon">{isSearching ? '🔍' : '📝'}</div>
            <div className="empty-state-text">
              {isSearching ? '没有找到匹配的笔记' : '暂无笔记'}
            </div>
            <div className="empty-state-hint">
              {isSearching ? '尝试其他关键词' : '点击 + 按钮创建第一个笔记'}
            </div>
          </div>
        ) : (
          notes.map(note => (
            <div
              key={note.note_id}
              className={`note-item ${currentNoteId === note.note_id ? 'active' : ''}`}
              onClick={() => onSelectNote(note.note_id)}
              onContextMenu={(e) => handleContextMenu(e, note.note_id)}
            >
              <div className="note-item-title">{note.title || '无标题'}</div>
              <div className="note-item-preview">
                {stripMarkdown(note.content) || '点击开始编辑...'}
              </div>
              <div className="note-item-meta">
                {note.tags.length > 0 && (
                  <div className="note-item-tags">
                    {note.tags.slice(0, 3).map((tag, idx) => (
                      <span key={idx} className="tag-pill">
                        {tag}
                      </span>
                    ))}
                    {note.tags.length > 3 && (
                      <span className="tag-pill">+{note.tags.length - 3}</span>
                    )}
                  </div>
                )}
                <span className="note-item-date">{formatDate(note.updated_at)}</span>
              </div>
            </div>
          ))
        )}
      </div>

      {contextMenu && (
        <div
          className="context-menu"
          style={{ left: contextMenu.x, top: contextMenu.y }}
        >
          <div className="context-menu-item danger" onClick={handleDelete}>
            🗑️ 删除笔记
          </div>
        </div>
      )}
    </div>
  );
};
