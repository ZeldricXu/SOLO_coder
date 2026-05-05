import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { createEditor, Descendant, Editor as SlateEditor, Transforms, Element as SlateElement, Text, Range } from 'slate';
import { Slate, Editable, withReact, useSlate, useSlateStatic, RenderElementProps, RenderLeafProps } from 'slate-react';
import { withHistory } from 'slate-history';
import isHotkey from 'is-hotkey';
import { format } from 'date-fns';
import { zhCN } from 'date-fns/locale';
import { Note, Tag as TagType } from '../../shared/types';
import { MarkdownParser } from '../../shared/utils/markdown-parser';

interface EditorProps {
  note: Note;
  onNoteUpdated: () => void;
  tags: TagType[];
}

type CustomElement = { type: string; children: CustomText[]; align?: string };
type CustomText = { text: string; bold?: boolean; italic?: boolean; underline?: boolean; code?: boolean; strikethrough?: boolean };

declare module 'slate' {
  interface CustomTypes {
    Editor: SlateEditor;
    Element: CustomElement;
    Text: CustomText;
  }
}

const LIST_TYPES = ['numbered-list', 'bulleted-list'];
const TEXT_ALIGN_TYPES = ['left', 'center', 'right', 'justify'];

const initialValue: Descendant[] = [
  {
    type: 'paragraph',
    children: [{ text: '' }],
  },
];

const Element: React.FC<RenderElementProps> = ({ attributes, children, element }) => {
  const style: React.CSSProperties = { textAlign: element.align };

  switch (element.type) {
    case 'block-quote':
      return <blockquote style={style} {...attributes}>{children}</blockquote>;
    case 'bulleted-list':
      return <ul style={style} {...attributes}>{children}</ul>;
    case 'heading-one':
      return <h1 style={style} {...attributes}>{children}</h1>;
    case 'heading-two':
      return <h2 style={style} {...attributes}>{children}</h2>;
    case 'heading-three':
      return <h3 style={style} {...attributes}>{children}</h3>;
    case 'list-item':
      return <li style={style} {...attributes}>{children}</li>;
    case 'numbered-list':
      return <ol style={style} {...attributes}>{children}</ol>;
    case 'code-block':
      return (
        <pre style={style} {...attributes}>
          <code>{children}</code>
        </pre>
      );
    default:
      return <p style={style} {...attributes}>{children}</p>;
  }
};

const Leaf: React.FC<RenderLeafProps> = ({ attributes, children, leaf }) => {
  if (leaf.bold) {
    children = <strong>{children}</strong>;
  }

  if (leaf.code) {
    children = <code>{children}</code>;
  }

  if (leaf.italic) {
    children = <em>{children}</em>;
  }

  if (leaf.underline) {
    children = <u>{children}</u>;
  }

  if (leaf.strikethrough) {
    children = <s>{children}</s>;
  }

  return <span {...attributes}>{children}</span>;
};

const MarkButton: React.FC<{
  format: string;
  icon: string;
}> = ({ format, icon }) => {
  const editor = useSlateStatic();
  const isActive = isMarkActive(editor, format);

  return (
    <button
      className={`toolbar-button ${isActive ? 'active' : ''}`}
      onMouseDown={(e) => {
        e.preventDefault();
        toggleMark(editor, format);
      }}
    >
      {icon}
    </button>
  );
};

const BlockButton: React.FC<{
  format: string;
  icon: string;
}> = ({ format, icon }) => {
  const editor = useSlateStatic();
  const isActive = isBlockActive(editor, format);

  return (
    <button
      className={`toolbar-button ${isActive ? 'active' : ''}`}
      onMouseDown={(e) => {
        e.preventDefault();
        toggleBlock(editor, format);
      }}
    >
      {icon}
    </button>
  );
};

const isMarkActive = (editor: SlateEditor, format: string): boolean => {
  const marks = SlateEditor.marks(editor);
  return marks ? marks[format as keyof typeof marks] === true : false;
};

const isBlockActive = (editor: SlateEditor, format: string): boolean => {
  const [match] = SlateEditor.nodes(editor, {
    match: (n) => !SlateEditor.isEditor(n) && SlateElement.isElement(n) && n.type === format,
  });
  return !!match;
};

const toggleMark = (editor: SlateEditor, format: string): void => {
  const isActive = isMarkActive(editor, format);

  if (isActive) {
    SlateEditor.removeMark(editor, format);
  } else {
    SlateEditor.addMark(editor, format, true);
  }
};

const toggleBlock = (editor: SlateEditor, format: string): void => {
  const isActive = isBlockActive(editor, format);
  const isList = LIST_TYPES.includes(format);
  const isBlockQuote = format === 'block-quote';

  Transforms.unwrapNodes(editor, {
    match: (n) =>
      !SlateEditor.isEditor(n) &&
      SlateElement.isElement(n) && LIST_TYPES.includes(n.type),
    split: true,
  });

  const newProperties: Partial<SlateElement> = {
    type: isActive ? 'paragraph' : isList ? 'list-item' : format,
  };

  Transforms.setNodes<SlateElement>(editor, newProperties);

  if (!isActive && isList) {
    const block = { type: format, children: [] };
    Transforms.wrapNodes(editor, block);
  }
};

const Toolbar: React.FC = () => {
  return (
    <div className="editor-toolbar">
      <BlockButton format="heading-one" icon="H1" />
      <BlockButton format="heading-two" icon="H2" />
      <BlockButton format="heading-three" icon="H3" />
      <div className="toolbar-separator" />
      <MarkButton format="bold" icon="B" />
      <MarkButton format="italic" icon="I" />
      <MarkButton format="underline" icon="U" />
      <MarkButton format="code" icon="</>" />
      <div className="toolbar-separator" />
      <BlockButton format="block-quote" icon="❝" />
      <BlockButton format="bulleted-list" icon="•" />
      <BlockButton format="numbered-list" icon="1." />
      <BlockButton format="code-block" icon="⌨️" />
    </div>
  );
};

export const Editor: React.FC<EditorProps> = ({ note, onNoteUpdated, tags }) => {
  const [title, setTitle] = useState(note.title);
  const [tagInput, setTagInput] = useState('');
  const [aiSummary, setAiSummary] = useState<string | null>(note.ai_summary);
  const [aiKeywords, setAiKeywords] = useState<string[]>([]);
  const [isGeneratingSummary, setIsGeneratingSummary] = useState(false);
  const autoSaveTimer = useRef<NodeJS.Timeout | null>(null);
  const parser = useMemo(() => new MarkdownParser(), []);

  const editor = useMemo(
    () => withHistory(withReact(createEditor())),
    []
  );

  const initialEditorValue = useMemo(() => {
    return parser.parse(note.content).slateValue;
  }, [note.note_id, parser]);

  const [editorValue, setEditorValue] = useState<Descendant[]>(initialEditorValue);

  useEffect(() => {
    setTitle(note.title);
    setAiSummary(note.ai_summary);
    setEditorValue(parser.parse(note.content).slateValue);
  }, [note.note_id, parser]);

  const saveNote = useCallback(async (newTitle?: string, newContent?: string, newTags?: string[]) => {
    if (!window.electronAPI) return;

    try {
      const updates: {
        note_id: string;
        title?: string;
        content?: string;
        tags?: string[];
      } = { note_id: note.note_id };

      if (newTitle !== undefined) updates.title = newTitle;
      if (newContent !== undefined) updates.content = newContent;
      if (newTags !== undefined) updates.tags = newTags;

      await window.electronAPI.note.update(updates);
      onNoteUpdated();
    } catch (error) {
      console.error('Failed to save note:', error);
    }
  }, [note.note_id, onNoteUpdated]);

  const debouncedSave = useCallback((newTitle?: string, newContent?: string, newTags?: string[]) => {
    if (autoSaveTimer.current) {
      clearTimeout(autoSaveTimer.current);
    }

    autoSaveTimer.current = setTimeout(() => {
      saveNote(newTitle, newContent, newTags);
    }, 500);
  }, [saveNote]);

  const handleTitleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const newTitle = e.target.value;
    setTitle(newTitle);
    debouncedSave(newTitle);
  };

  const handleEditorChange = (newValue: Descendant[]) => {
    setEditorValue(newValue);
    const markdown = parser.serialize(newValue).markdown;
    debouncedSave(undefined, markdown);
  };

  const handleTagInputKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      const newTag = tagInput.trim().replace(',', '');
      if (newTag && !note.tags.includes(newTag)) {
        const newTags = [...note.tags, newTag];
        saveNote(undefined, undefined, newTags);
      }
      setTagInput('');
    } else if (e.key === 'Backspace' && tagInput === '' && note.tags.length > 0) {
      const newTags = note.tags.slice(0, -1);
      saveNote(undefined, undefined, newTags);
    }
  };

  const removeTag = (tagToRemove: string) => {
    const newTags = note.tags.filter(t => t !== tagToRemove);
    saveNote(undefined, undefined, newTags);
  };

  const generateSummary = async () => {
    if (!window.electronAPI) return;

    setIsGeneratingSummary(true);
    try {
      const content = parser.serialize(editorValue).markdown;
      const result = await window.electronAPI.ai.generateSummary(content);

      if (result.success && result.data) {
        setAiSummary(result.data.summary);
        setAiKeywords(result.data.keywords);
        
        await window.electronAPI.note.update({
          note_id: note.note_id,
          ai_summary: result.data.summary,
        });
        onNoteUpdated();
      }
    } catch (error) {
      console.error('Failed to generate summary:', error);
    } finally {
      setIsGeneratingSummary(false);
    }
  };

  const formatDate = (dateStr: string) => {
    try {
      return format(new Date(dateStr), 'yyyy年MM月dd日 HH:mm', { locale: zhCN });
    } catch {
      return dateStr;
    }
  };

  const renderPlaceholder = (
    <span className="editor-placeholder">
      开始输入内容...
    </span>
  );

  return (
    <div className="editor-container">
      <div className="editor-header">
        <input
          type="text"
          className="editor-title-input"
          placeholder="笔记标题"
          value={title}
          onChange={handleTitleChange}
        />
        <div className="editor-tags-row">
          {note.tags.map((tag, idx) => (
            <span key={idx} className="tag-pill" style={{ cursor: 'pointer' }} onClick={() => removeTag(tag)}>
              {tag} ×
            </span>
          ))}
          <input
            type="text"
            className="editor-tag-input"
            placeholder="添加标签... (回车确认)"
            value={tagInput}
            onChange={(e) => setTagInput(e.target.value)}
            onKeyDown={handleTagInputKeyDown}
          />
        </div>
        <div className="editor-meta">
          <span>创建于 {formatDate(note.created_at)}</span>
          <span>更新于 {formatDate(note.updated_at)}</span>
          <span>{note.word_count} 字</span>
        </div>
      </div>

      <Toolbar />

      <div className="editor-content">
        <Slate editor={editor} initialValue={editorValue} onChange={handleEditorChange}>
          <Editable
            className="slate-editor"
            renderElement={(props) => <Element {...props} />}
            renderLeaf={(props) => <Leaf {...props} />}
            placeholder={renderPlaceholder}
            onKeyDown={(event) => {
              if (isHotkey('mod+b', event)) {
                event.preventDefault();
                toggleMark(editor, 'bold');
              }

              if (isHotkey('mod+i', event)) {
                event.preventDefault();
                toggleMark(editor, 'italic');
              }

              if (isHotkey('mod+u', event)) {
                event.preventDefault();
                toggleMark(editor, 'underline');
              }

              if (isHotkey('mod+`', event)) {
                event.preventDefault();
                toggleMark(editor, 'code');
              }
            }}
          />
        </Slate>
      </div>

      {aiSummary && (
        <div className="ai-summary-panel">
          <div className="ai-summary-header">
            <span className="ai-summary-title">AI 摘要</span>
            <button
              className="toolbar-button"
              onClick={generateSummary}
              disabled={isGeneratingSummary}
            >
              {isGeneratingSummary ? <span className="loading" /> : '重新生成'}
            </button>
          </div>
          <p className="ai-summary-content">{aiSummary}</p>
          {aiKeywords.length > 0 && (
            <div className="ai-summary-keywords">
              {aiKeywords.map((kw, idx) => (
                <span key={idx} className="ai-keyword-pill">{kw}</span>
              ))}
            </div>
          )}
        </div>
      )}

      {!aiSummary && (
        <div className="ai-summary-panel">
          <div className="ai-summary-header">
            <span className="ai-summary-title">AI 摘要</span>
            <button
              className="toolbar-button"
              onClick={generateSummary}
              disabled={isGeneratingSummary}
            >
              {isGeneratingSummary ? <span className="loading" /> : '生成摘要'}
            </button>
          </div>
          <p className="ai-summary-content" style={{ color: '#999', fontStyle: 'italic' }}>
            点击"生成摘要"按钮，AI 将自动为您提取笔记的核心内容和关键词
          </p>
        </div>
      )}
    </div>
  );
};
