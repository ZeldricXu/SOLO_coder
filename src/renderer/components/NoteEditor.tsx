import React, { useCallback, useMemo, useState, useEffect, useRef } from 'react';
import { createEditor, Descendant, Element as SlateElement, Text, Transforms, Range, Editor, Point } from 'slate';
import { Slate, Editable, withReact, ReactEditor, useSlate, useFocused } from 'slate-react';
import { withHistory } from 'slate-history';
import clsx from 'clsx';
import type { Note } from '@shared/types';
import './editor.css';

interface WikiLinkElement {
  type: 'wiki-link';
  target: string;
  displayText: string;
  children: { text: string }[];
}

interface CodeBlockElement {
  type: 'code-block';
  language?: string;
  children: { text: string }[];
}

interface HeadingElement {
  type: 'heading';
  level: number;
  children: { text: string }[];
}

interface BlockquoteElement {
  type: 'blockquote';
  children: { text: string }[];
}

interface ListElement {
  type: 'bulleted-list' | 'numbered-list';
  children: { text: string }[];
}

interface ListItemElement {
  type: 'list-item';
  children: { text: string }[];
}

interface ParagraphElement {
  type: 'paragraph';
  children: { text: string }[];
}

type CustomElement =
  | WikiLinkElement
  | CodeBlockElement
  | HeadingElement
  | BlockquoteElement
  | ListElement
  | ListItemElement
  | ParagraphElement;

interface EditorProps {
  note: Note | null;
  onSave?: (content: string) => void;
  onLinkClick?: (target: string) => void;
}

const withWikiLinks = (editor: ReactEditor) => {
  const { isInline, isVoid } = editor;
  
  editor.isInline = (element: any) => {
    return element.type === 'wiki-link' ? true : isInline(element);
  };
  
  editor.isVoid = (element: any) => {
    return element.type === 'wiki-link' ? false : isVoid(element);
  };
  
  return editor;
};

function parseMarkdownToSlate(markdown: string): Descendant[] {
  const lines = markdown.split('\n');
  const nodes: Descendant[] = [];
  let i = 0;
  
  while (i < lines.length) {
    const line = lines[i];
    
    if (line.startsWith('# ')) {
      nodes.push({
        type: 'heading',
        level: 1,
        children: [{ text: line.slice(2) }],
      } as any);
      i++;
      continue;
    }
    
    if (line.startsWith('## ')) {
      nodes.push({
        type: 'heading',
        level: 2,
        children: [{ text: line.slice(3) }],
      } as any);
      i++;
      continue;
    }
    
    if (line.startsWith('### ')) {
      nodes.push({
        type: 'heading',
        level: 3,
        children: [{ text: line.slice(4) }],
      } as any);
      i++;
      continue;
    }
    
    if (line.startsWith('> ')) {
      const quoteLines: string[] = [];
      while (i < lines.length && lines[i].startsWith('> ')) {
        quoteLines.push(lines[i].slice(2));
        i++;
      }
      nodes.push({
        type: 'blockquote',
        children: [{ text: quoteLines.join('\n') }],
      } as any);
      continue;
    }
    
    if (line.startsWith('```')) {
      const language = line.slice(3).trim();
      const codeLines: string[] = [];
      i++;
      while (i < lines.length && !lines[i].startsWith('```')) {
        codeLines.push(lines[i]);
        i++;
      }
      i++;
      nodes.push({
        type: 'code-block',
        language,
        children: [{ text: codeLines.join('\n') }],
      } as any);
      continue;
    }
    
    if (line.startsWith('- ') || line.startsWith('* ')) {
      const items: string[] = [];
      while (i < lines.length && (lines[i].startsWith('- ') || lines[i].startsWith('* '))) {
        items.push(lines[i].slice(2));
        i++;
      }
      nodes.push({
        type: 'bulleted-list',
        children: items.map(item => ({
          type: 'list-item',
          children: parseInlineText(item),
        })),
      } as any);
      continue;
    }
    
    if (/^\d+\.\s/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\d+\.\s/.test(lines[i])) {
        items.push(lines[i].replace(/^\d+\.\s/, ''));
        i++;
      }
      nodes.push({
        type: 'numbered-list',
        children: items.map(item => ({
          type: 'list-item',
          children: parseInlineText(item),
        })),
      } as any);
      continue;
    }
    
    if (line.trim() === '') {
      nodes.push({ type: 'paragraph', children: [{ text: '' }] } as any);
      i++;
      continue;
    }
    
    nodes.push({
      type: 'paragraph',
      children: parseInlineText(line),
    } as any);
    i++;
  }
  
  if (nodes.length === 0) {
    nodes.push({ type: 'paragraph', children: [{ text: '' }] } as any);
  }
  
  return nodes;
}

function parseInlineText(text: string): any[] {
  const children: any[] = [];
  const wikiLinkRegex = /\[\[([^\[\]|]+)(?:\|([^\[\]]+))?\]\]/g;
  let lastIndex = 0;
  let match;
  
  while ((match = wikiLinkRegex.exec(text)) !== null) {
    if (match.index > lastIndex) {
      children.push({ text: text.slice(lastIndex, match.index) });
    }
    
    const target = match[1].trim();
    const displayText = (match[2] || match[1]).trim();
    
    children.push({
      type: 'wiki-link',
      target,
      displayText,
      children: [{ text: displayText }],
    });
    
    lastIndex = match.index + match[0].length;
  }
  
  if (lastIndex < text.length) {
    children.push({ text: text.slice(lastIndex) });
  }
  
  if (children.length === 0) {
    children.push({ text: '' });
  }
  
  return children;
}

function slateToMarkdown(nodes: Descendant[]): string {
  const lines: string[] = [];
  
  for (const node of nodes) {
    const element = node as any;
    
    switch (element.type) {
      case 'heading':
        const prefix = '#'.repeat(element.level);
        lines.push(`${prefix} ${element.children.map((c: any) => c.text).join('')}`);
        break;
      case 'paragraph':
        lines.push(inlineToMarkdown(element.children));
        break;
      case 'blockquote':
        const quoteText = element.children.map((c: any) => c.text).join('\n');
        lines.push(quoteText.split('\n').map(l => `> ${l}`).join('\n'));
        break;
      case 'code-block':
        lines.push(`\`\`\`${element.language || ''}`);
        lines.push(element.children[0].text);
        lines.push('```');
        break;
      case 'bulleted-list':
        for (const item of element.children) {
          lines.push(`- ${inlineToMarkdown(item.children)}`);
        }
        break;
      case 'numbered-list':
        element.children.forEach((item: any, idx: number) => {
          lines.push(`${idx + 1}. ${inlineToMarkdown(item.children)}`);
        });
        break;
      default:
        if (element.text !== undefined) {
          lines.push(element.text);
        } else if (element.children) {
          lines.push(inlineToMarkdown(element.children));
        }
    }
  }
  
  return lines.join('\n');
}

function inlineToMarkdown(children: any[]): string {
  return children.map(child => {
    if (child.type === 'wiki-link') {
      if (child.displayText !== child.target) {
        return `[[${child.target}|${child.displayText}]]`;
      }
      return `[[${child.target}]]`;
    }
    return child.text || '';
  }).join('');
}

const WikiLinkElement: React.FC<{
  attributes: any;
  element: WikiLinkElement;
  children: React.ReactNode;
}> = ({ attributes, element, children }) => {
  const [showPreview, setShowPreview] = useState(false);
  const [previewNote, setPreviewNote] = useState<Note | null>(null);
  const [hoverPos, setHoverPos] = useState({ x: 0, y: 0 });
  const linkRef = useRef<HTMLSpanElement>(null);
  
  const handleMouseEnter = async (e: React.MouseEvent) => {
    setShowPreview(true);
    setHoverPos({ x: e.clientX, y: e.clientY });
    
    const allNotes = await window.api.notes.getAll();
    const targetPath = element.target.endsWith('.md')
      ? element.target
      : element.target + '.md';
    const found = allNotes.find(n => n.path.endsWith(targetPath) || n.title === element.target);
    setPreviewNote(found || null);
  };
  
  const handleClick = (e: React.MouseEvent) => {
    e.preventDefault();
    const customEvent = new CustomEvent('wikilink-click', { detail: element.target });
    document.dispatchEvent(customEvent);
  };
  
  return (
    <span
      ref={linkRef}
      {...attributes}
      className="wiki-link"
      onClick={handleClick}
      onMouseEnter={handleMouseEnter}
      onMouseLeave={() => setShowPreview(false)}
    >
      {children}
      {showPreview && (
        <div
          className="wiki-link-preview"
          style={{
            left: Math.max(10, hoverPos.x - 150),
            top: hoverPos.y + 20,
          }}
        >
          {previewNote ? (
            <>
              <div className="preview-title">{previewNote.title}</div>
              <div className="preview-content">
                {previewNote.content.slice(0, 200)}
                {previewNote.content.length > 200 && '...'}
              </div>
              {previewNote.tags.length > 0 && (
                <div className="preview-tags">
                  {previewNote.tags.map(tag => (
                    <span key={tag} className="preview-tag">#{tag}</span>
                  ))}
                </div>
              )}
            </>
          ) : (
            <div className="preview-empty">未找到笔记: {element.target}</div>
          )}
        </div>
      )}
    </span>
  );
};

const LinkAutocomplete: React.FC<{
  position: { top: number; left: number } | null;
  searchText: string;
  onSelect: (target: string) => void;
}> = ({ position, searchText, onSelect }) => {
  const [suggestions, setSuggestions] = useState<Note[]>([]);
  const [selectedIndex, setSelectedIndex] = useState(0);
  
  useEffect(() => {
    const fetchSuggestions = async () => {
      if (!searchText) {
        const notes = await window.api.notes.getAll();
        setSuggestions(notes.slice(0, 10));
      } else {
        const results = await window.api.search.query(searchText, { limit: 10 });
        const notes = await Promise.all(
          results.map(async r => {
            const note = await window.api.notes.getById(r.id);
            return note;
          })
        );
        setSuggestions(notes.filter(Boolean) as Note[]);
      }
      setSelectedIndex(0);
    };
    
    fetchSuggestions();
  }, [searchText]);
  
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (!position) return;
      
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSelectedIndex(prev => Math.min(prev + 1, suggestions.length - 1));
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSelectedIndex(prev => Math.max(prev - 1, 0));
      } else if (e.key === 'Enter') {
        e.preventDefault();
        if (suggestions[selectedIndex]) {
          onSelect(suggestions[selectedIndex].title);
        }
      } else if (e.key === 'Escape') {
        e.preventDefault();
      }
    };
    
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [position, suggestions, selectedIndex, onSelect]);
  
  if (!position || suggestions.length === 0) return null;
  
  return (
    <div
      className="link-autocomplete"
      style={{ top: position.top, left: position.left }}
    >
      {suggestions.map((note, index) => (
        <div
          key={note.id}
          className={clsx('autocomplete-item', { selected: index === selectedIndex })}
          onClick={() => onSelect(note.title)}
          onMouseEnter={() => setSelectedIndex(index)}
        >
          <span className="autocomplete-icon">📄</span>
          <span className="autocomplete-title">{note.title}</span>
          {note.tags.length > 0 && (
            <span className="autocomplete-tags">
              {note.tags.slice(0, 2).map(t => `#${t}`).join(' ')}
            </span>
          )}
        </div>
      ))}
    </div>
  );
};

const NoteEditor: React.FC<EditorProps> = ({ note, onSave, onLinkClick }) => {
  const editor = useMemo(
    () => withWikiLinks(withReact(withHistory(createEditor()))),
    []
  );
  
  const [autocompletePos, setAutocompletePos] = useState<{ top: number; left: number } | null>(null);
  const [autocompleteSearch, setAutocompleteSearch] = useState('');
  const [wikiLinkStart, setWikiLinkStart] = useState<Point | null>(null);
  
  const initialValue = useMemo(() => {
    if (note) {
      return parseMarkdownToSlate(note.content);
    }
    return [{ type: 'paragraph', children: [{ text: '' }] }] as Descendant[];
  }, [note?.id]);
  
  useEffect(() => {
    if (note) {
      const newValue = parseMarkdownToSlate(note.content);
      Transforms.deselect(editor);
    }
  }, [note?.id]);
  
  const renderElement = useCallback((props: any) => {
    const { element } = props;
    
    switch (element.type) {
      case 'wiki-link':
        return <WikiLinkElement {...props} />;
      case 'heading':
        const Tag = `h${element.level}` as keyof JSX.IntrinsicElements;
        return React.createElement(Tag, { className: `heading heading-${element.level}`, ...props.attributes }, props.children);
      case 'paragraph':
        return <p {...props.attributes} className="paragraph">{props.children}</p>;
      case 'blockquote':
        return <blockquote {...props.attributes} className="blockquote">{props.children}</blockquote>;
      case 'code-block':
        return (
          <pre {...props.attributes} className={`code-block language-${element.language || 'plain'}`}>
            <code>{props.children}</code>
          </pre>
        );
      case 'bulleted-list':
        return <ul {...props.attributes} className="bulleted-list">{props.children}</ul>;
      case 'numbered-list':
        return <ol {...props.attributes} className="numbered-list">{props.children}</ol>;
      case 'list-item':
        return <li {...props.attributes} className="list-item">{props.children}</li>;
      default:
        return <p {...props.attributes}>{props.children}</p>;
    }
  }, []);
  
  const renderLeaf = useCallback((props: any) => {
    let { attributes, children, leaf } = props;
    
    if (leaf.bold) {
      children = <strong>{children}</strong>;
    }
    
    if (leaf.italic) {
      children = <em>{children}</em>;
    }
    
    if (leaf.code) {
      children = <code>{children}</code>;
    }
    
    if (leaf.underline) {
      children = <u>{children}</u>;
    }
    
    return <span {...attributes}>{children}</span>;
  }, []);
  
  const handleLinkAutocomplete = useCallback((editor: ReactEditor) => {
    const { selection } = editor;
    if (!selection || !Range.isCollapsed(selection)) {
      setAutocompletePos(null);
      return;
    }
    
    const { anchor } = selection;
    const { path, offset } = anchor;
    
    const textNode = Editor.node(editor, path)[0] as any;
    const text = textNode.text || '';
    const beforeCursor = text.slice(0, offset);
    
    const bracketMatch = beforeCursor.match(/\[\[([^\[\]]*)$/);
    
    if (bracketMatch) {
      const searchText = bracketMatch[1];
      setAutocompleteSearch(searchText);
      setWikiLinkStart(anchor);
      
      try {
        const domRange = ReactEditor.toDOMRange(editor, {
          anchor: { path, offset: offset - bracketMatch[0].length },
          focus: anchor,
        });
        const rect = domRange.getBoundingClientRect();
        setAutocompletePos({
          top: rect.bottom + 4,
          left: rect.left,
        });
      } catch {
        setAutocompletePos(null);
      }
    } else {
      setAutocompletePos(null);
      setWikiLinkStart(null);
    }
  }, []);
  
  const handleSelectLink = useCallback((target: string) => {
    if (!wikiLinkStart) return;
    
    const editorEl = editor as any;
    const { selection } = editorEl;
    if (!selection) return;
    
    const { anchor } = selection;
    const startOffset = anchor.offset - autocompleteSearch.length - 2;
    
    Transforms.delete(editorEl, {
      at: {
        anchor: { ...anchor, offset: Math.max(0, startOffset) },
        focus: anchor,
      },
    });
    
    const linkNode = {
      type: 'wiki-link',
      target,
      displayText: target,
      children: [{ text: target }],
    };
    
    Transforms.insertNodes(editorEl, linkNode as any);
    Transforms.move(editorEl, { distance: 1, unit: 'offset' });
    
    setAutocompletePos(null);
    setWikiLinkStart(null);
  }, [autocompleteSearch.length, wikiLinkStart]);
  
  const onChange = useCallback((value: Descendant[]) => {
    handleLinkAutocomplete(editor as ReactEditor);
    
    if (note && onSave) {
      clearTimeout((window as any)._saveTimeout);
      (window as any)._saveTimeout = setTimeout(() => {
        const markdown = slateToMarkdown(value);
        onSave(markdown);
      }, 500);
    }
  }, [note, onSave, editor, handleLinkAutocomplete]);
  
  useEffect(() => {
    const handleLinkClick = (e: any) => {
      if (onLinkClick) {
        onLinkClick(e.detail);
      }
    };
    
    document.addEventListener('wikilink-click', handleLinkClick as EventListener);
    return () => document.removeEventListener('wikilink-click', handleLinkClick as EventListener);
  }, [onLinkClick]);
  
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
      <div className="editor-content">
        <Slate editor={editor as any} initialValue={initialValue} onChange={onChange}>
          <Editable
            renderElement={renderElement}
            renderLeaf={renderLeaf}
            placeholder="开始写作，使用 [[ 创建双链..."
            className="slate-editor"
            spellCheck={false}
          />
          <LinkAutocomplete
            position={autocompletePos}
            searchText={autocompleteSearch}
            onSelect={handleSelectLink}
          />
        </Slate>
      </div>
    </div>
  );
};

export default NoteEditor;
export { NoteEditor, parseMarkdownToSlate, slateToMarkdown };
