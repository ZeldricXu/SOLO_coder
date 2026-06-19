import React, { useCallback, useMemo, useEffect, useState, useRef } from 'react';
import { Descendant, Transforms } from 'slate';
import { Slate, Editable, withReact, ReactEditor } from 'slate-react';
import { withHistory } from 'slate-history';
import clsx from 'clsx';
import type { Note } from '@shared/types';
import { useEditorStore } from '../../stores/editorStore';
import { parseMarkdownToSlate, slateToMarkdown, type WikiLinkElement as WikiLinkElementType } from '../../utils/slateConverter';
import { LinkAutocomplete } from './LinkAutocomplete';

const WikiLinkElement: React.FC<{
  attributes: any;
  element: WikiLinkElementType;
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

interface EditorCanvasProps {
  note: Note;
  onSave?: (content: string) => void;
  onLinkClick?: (target: string) => void;
  onInsertImage?: (relativePath: string) => void;
}

export const EditorCanvas: React.FC<EditorCanvasProps> = ({ note, onSave, onLinkClick, onInsertImage }) => {
  const {
    editorInstance,
    autocompletePos,
    autocompleteSearch,
    isDragging,
    setIsDragging,
    handleLinkAutocomplete,
    handleSelectLink,
    insertImage,
  } = useEditorStore();

  const initialValue = useMemo(() => {
    return parseMarkdownToSlate(note.content);
  }, [note.id]);

  useEffect(() => {
    if (note) {
      Transforms.deselect(editorInstance as any);
    }
  }, [note.id]);

  const renderElement = useCallback((props: any) => {
    const { element } = props;

    switch (element.type) {
      case 'wiki-link':
        return <WikiLinkElement {...props} />;
      case 'image':
        return (
          <div {...props.attributes} className="image-block">
            <img
              src={element.src}
              alt={element.alt}
              className="editor-image"
            />
            {element.alt && (
              <div className="image-caption">{element.alt}</div>
            )}
          </div>
        );
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

  const onChange = useCallback((value: Descendant[]) => {
    handleLinkAutocomplete(editorInstance as ReactEditor);

    if (note && onSave) {
      clearTimeout((window as any)._saveTimeout);
      (window as any)._saveTimeout = setTimeout(() => {
        const markdown = slateToMarkdown(value);
        onSave(markdown);
      }, 500);
    }
  }, [note, onSave, editorInstance, handleLinkAutocomplete]);

  const handleInsertImage = useCallback((src: string, alt = '') => {
    insertImage(src, alt);
    if (onInsertImage) {
      onInsertImage(src);
    }
  }, [insertImage, onInsertImage]);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'copy';
    setIsDragging(true);
  }, [setIsDragging]);

  const handleDragLeave = useCallback(() => {
    setIsDragging(false);
  }, [setIsDragging]);

  const handleDrop = useCallback(async (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);

    const files = Array.from(e.dataTransfer.files || []);
    if (files.length === 0) return;

    const imageFiles = files.filter(f => f.type.startsWith('image/'));
    if (imageFiles.length === 0) return;

    try {
      for (const file of imageFiles) {
        const arrayBuffer = await file.arrayBuffer();
        const buffer = Buffer.from(arrayBuffer);

        const result = await window.api.attachments.upload({
          name: file.name,
          type: file.type,
          size: file.size,
          data: buffer,
        });

        const uploadResult = result as { success?: boolean; relativePath?: string };
        if (uploadResult.success && uploadResult.relativePath) {
          handleInsertImage(uploadResult.relativePath, file.name);
        }
      }
    } catch (err) {
      console.error('Error uploading images:', err);
    }
  }, [handleInsertImage, setIsDragging]);

  useEffect(() => {
    const handleLinkClickEvent = (e: any) => {
      if (onLinkClick) {
        onLinkClick(e.detail);
      }
    };

    document.addEventListener('wikilink-click', handleLinkClickEvent as EventListener);
    return () => document.removeEventListener('wikilink-click', handleLinkClickEvent as EventListener);
  }, [onLinkClick]);

  return (
    <div
      className={clsx('editor-content', { 'editor-dragging': isDragging })}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      {isDragging && (
        <div className="editor-drop-overlay">
          <div className="editor-drop-icon">🖼️</div>
          <div className="editor-drop-text">释放以插入图片</div>
        </div>
      )}
      <Slate editor={editorInstance as any} initialValue={initialValue} onChange={onChange}>
        <Editable
          renderElement={renderElement}
          renderLeaf={renderLeaf}
          placeholder="开始写作，使用 [[ 创建双链... 或拖入图片"
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
  );
};
