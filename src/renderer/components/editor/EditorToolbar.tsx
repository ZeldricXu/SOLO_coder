import React from 'react';
import type { Note } from '@shared/types';

interface EditorToolbarProps {
  note: Note;
}

export const EditorToolbar: React.FC<EditorToolbarProps> = ({ note }) => {
  return (
    <div className="editor-header">
      <h1 className="note-title">{note.title}</h1>
      <div className="note-meta">
        {note.tags.length > 0 && (
          <div className="note-tags">
            {note.tags.map(tag => (
              <span key={tag} className="note-tag">#{tag}</span>
            ))}
          </div>
        )}
        <span className="note-path">{note.path}</span>
      </div>
    </div>
  );
};
