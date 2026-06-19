import React from 'react';
import type { Note } from '@shared/types';
import './editor.css';
import './brokenLinkFixer.css';
import { EditorToolbar, EditorCanvas, BacklinkPanel } from './editor';
import { useEditorStore } from '../stores/editorStore';

interface EditorProps {
  note: Note | null;
  allNotes: Note[];
  onSave?: (content: string) => void;
  onLinkClick?: (target: string) => void;
  onInsertImage?: (relativePath: string) => void;
}

const NoteEditor: React.FC<EditorProps> = ({ note, allNotes, onSave, onLinkClick, onInsertImage }) => {
  const resetEditor = useEditorStore(state => state.resetEditor);

  React.useEffect(() => {
    resetEditor(note?.id ?? null);
  }, [note?.id, resetEditor]);

  if (!note) {
    return (
      <div className="editor-empty">
        <div className="editor-empty-icon">📝</div>
        <h2>选择一篇笔记开始编辑</h2>
        <p>或使用 ⌘P 搜索笔记</p>
      </div>
    );
  }

  return (
    <div className="editor-container">
      <EditorToolbar note={note} />
      <EditorCanvas
        note={note}
        onSave={onSave}
        onLinkClick={onLinkClick}
        onInsertImage={onInsertImage}
      />
      <BacklinkPanel
        note={note}
        allNotes={allNotes}
        onSave={onSave}
      />
    </div>
  );
};

export { NoteEditor };
export default NoteEditor;
export { parseMarkdownToSlate, slateToMarkdown } from './editor';
