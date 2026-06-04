'use client';

import { useEditor, EditorContent, Editor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Underline from '@tiptap/extension-underline';
import TextAlign from '@tiptap/extension-text-align';
import Highlight from '@tiptap/extension-highlight';
import Link from '@tiptap/extension-link';
import Image from '@tiptap/extension-image';
import Table from '@tiptap/extension-table';
import TableRow from '@tiptap/extension-table-row';
import TableCell from '@tiptap/extension-table-cell';
import TableHeader from '@tiptap/extension-table-header';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import CodeBlockLowlight from '@tiptap/extension-code-block-lowlight';
import Placeholder from '@tiptap/extension-placeholder';
import * as React from 'react';
import { useCallback, useEffect, useRef } from 'react';
import { EditorToolbar } from './EditorToolbar';
import { lowlight } from 'lowlight';
import type { CollabUser, AwarenessState } from '@/lib/collab/types';

interface TiptapEditorProps {
  content?: string;
  placeholder?: string;
  editable?: boolean;
  className?: string;
  toolbarClassName?: string;
  contentClassName?: string;
  onUpdate?: ({ editor }: { editor: Editor }) => void;
  onContentChange?: (content: string) => void;
  onSelectionUpdate?: ({ editor }: { editor: Editor }) => void;
  onFocus?: () => void;
  onBlur?: () => void;
  extensions?: any[];
  editorRef?: React.MutableRefObject<Editor | null>;
  showToolbar?: boolean;
  currentUser?: CollabUser;
  onCursorChange?: (pos: number, anchor: number, head: number) => void;
}

export function TiptapEditor({
  content = '',
  placeholder = '开始写作...',
  editable = true,
  className = '',
  toolbarClassName = '',
  contentClassName = '',
  onUpdate,
  onContentChange,
  onSelectionUpdate,
  onFocus,
  onBlur,
  extensions = [],
  editorRef: externalEditorRef,
  showToolbar = true,
  currentUser,
  onCursorChange,
}: TiptapEditorProps) {
  const internalEditorRef = useRef<Editor | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const defaultExtensions = [
    StarterKit.configure({
      heading: {
        levels: [1, 2, 3, 4, 5, 6],
      },
      codeBlock: false,
      bulletList: {
        keepMarks: true,
        keepAttributes: false,
      },
      orderedList: {
        keepMarks: true,
        keepAttributes: false,
      },
      blockquote: {
        HTMLAttributes: {
          class: 'border-l-4 border-gray-300 pl-4 italic text-gray-600',
        },
      },
      horizontalRule: {
        HTMLAttributes: {
          class: 'my-4 border-t border-gray-200',
        },
      },
    }),
    Underline,
    TextAlign.configure({
      types: ['heading', 'paragraph'],
    }),
    Highlight.configure({
      multicolor: true,
    }),
    Link.configure({
      openOnClick: false,
      HTMLAttributes: {
        class: 'text-blue-600 hover:underline',
        rel: 'noopener noreferrer',
        target: '_blank',
      },
    }),
    Image.configure({
      HTMLAttributes: {
        class: 'max-w-full h-auto rounded-lg',
      },
    }),
    Table.configure({
      resizable: true,
      HTMLAttributes: {
        class: 'border-collapse w-full',
      },
    }),
    TableRow,
    TableHeader.configure({
      HTMLAttributes: {
        class: 'bg-gray-100 font-semibold',
      },
    }),
    TableCell.configure({
      HTMLAttributes: {
        class: 'border border-gray-300 p-2',
      },
    }),
    TaskList.configure({
      HTMLAttributes: {
        class: 'list-none p-0 space-y-1',
      },
    }),
    TaskItem.configure({
      nested: true,
      HTMLAttributes: {
        class: 'flex items-start gap-2',
      },
    }),
    CodeBlockLowlight.configure({
      lowlight,
      HTMLAttributes: {
        class: 'rounded-lg bg-gray-900 text-gray-100 p-4 overflow-x-auto',
      },
    }),
    Placeholder.configure({
      placeholder,
      includeChildren: true,
    }),
  ];

  const handleUpdate = useCallback(({ editor }: { editor: Editor }) => {
    onUpdate?.({ editor });
    if (onContentChange) {
      onContentChange(editor.getHTML());
    }
  }, [onUpdate, onContentChange]);

  const handleSelectionUpdate = useCallback(({ editor }: { editor: Editor }) => {
    onSelectionUpdate?.({ editor });
    
    if (onCursorChange) {
      const { from, to, empty } = editor.state.selection;
      onCursorChange(
        empty ? editor.state.selection.anchor : from,
        editor.state.selection.anchor,
        editor.state.selection.head
      );
    }
  }, [onSelectionUpdate, onCursorChange]);

  const editor = useEditor({
    extensions: [...defaultExtensions, ...extensions],
    content,
    editable,
    onUpdate: handleUpdate,
    onSelectionUpdate: handleSelectionUpdate,
    onFocus: () => onFocus?.(),
    onBlur: () => onBlur?.(),
    editorProps: {
      attributes: {
        class: 'focus:outline-none',
      },
      handleKeyDown: (view, event) => {
        if (event.key === 'Tab') {
          event.preventDefault();
          if (event.shiftKey) {
            editor?.chain().focus().outdent().run();
          } else {
            editor?.chain().focus().indent().run();
          }
          return true;
        }
        return false;
      },
    },
  });

  useEffect(() => {
    if (editor) {
      internalEditorRef.current = editor;
      if (externalEditorRef) {
        externalEditorRef.current = editor;
      }
    }

    return () => {
      if (externalEditorRef) {
        externalEditorRef.current = null;
      }
    };
  }, [editor, externalEditorRef]);

  useEffect(() => {
    if (editor && content !== undefined) {
      const isSameContent = editor.getHTML() === content;
      if (!isSameContent && !editor.isFocused) {
        editor.commands.setContent(content, false);
      }
    }
  }, [content, editor]);

  useEffect(() => {
    if (editor) {
      editor.setEditable(editable);
    }
  }, [editable, editor]);

  return (
    <div ref={containerRef} className={`border border-gray-200 rounded-lg overflow-hidden ${className}`}>
      {showToolbar && editable && (
        <div className={toolbarClassName}>
          <EditorToolbar editor={editor} isEditable={editable} />
        </div>
      )}
      
      <div
        className={`prose prose-sm max-w-none p-4 min-h-[300px] ${
          editable ? 'cursor-text' : 'cursor-default'
        } ${contentClassName}`}
        style={{
          '--tw-prose-headings': 'color: inherit;',
          '--tw-prose-links': 'color: #2563eb;',
          '--tw-prose-code': 'color: #dc2626; background-color: #f3f4f6; padding: 0.125rem 0.25rem; border-radius: 0.25rem;',
          '--tw-prose-pre-code': 'color: inherit; background-color: inherit; padding: 0;',
          '--tw-prose-pre-bg': '#111827;',
          '--tw-prose-quotes': 'color: #6b7280; border-left-color: #d1d5db;',
        } as React.CSSProperties}
      >
        <EditorContent editor={editor} />
      </div>

      <style>{`
        .ProseMirror {
          outline: none;
          min-height: 200px;
        }
        
        .ProseMirror p.is-editor-empty:first-child::before {
          content: attr(data-placeholder);
          float: left;
          color: #adb5bd;
          pointer-events: none;
          height: 0;
        }
        
        .ProseMirror table {
          border-collapse: collapse;
          width: 100%;
          margin: 1rem 0;
        }
        
        .ProseMirror table td,
        .ProseMirror table th {
          border: 1px solid #e5e7eb;
          padding: 0.5rem 0.75rem;
          min-width: 100px;
        }
        
        .ProseMirror table th {
          background-color: #f9fafb;
          font-weight: 600;
        }
        
        .ProseMirror table tr:nth-child(even) {
          background-color: #f9fafb;
        }
        
        .ProseMirror code {
          background-color: #f3f4f6;
          padding: 0.125rem 0.25rem;
          border-radius: 0.25rem;
          font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
          font-size: 0.875em;
        }
        
        .ProseMirror pre {
          background-color: #111827;
          color: #f3f4f6;
          padding: 1rem;
          border-radius: 0.5rem;
          overflow-x: auto;
          margin: 1rem 0;
        }
        
        .ProseMirror pre code {
          background-color: transparent;
          padding: 0;
          color: inherit;
        }
        
        .ProseMirror blockquote {
          border-left: 4px solid #d1d5db;
          padding-left: 1rem;
          margin-left: 0;
          color: #6b7280;
          font-style: italic;
        }
        
        .ProseMirror hr {
          border: none;
          border-top: 2px solid #e5e7eb;
          margin: 2rem 0;
        }
        
        .ProseMirror ul[data-type="taskList"] {
          list-style: none;
          padding: 0;
        }
        
        .ProseMirror ul[data-type="taskList"] li {
          display: flex;
          align-items: flex-start;
          gap: 0.5rem;
          margin: 0.25rem 0;
        }
        
        .ProseMirror ul[data-type="taskList"] li label {
          flex-shrink: 0;
          margin-top: 0.25rem;
        }
        
        .ProseMirror ul[data-type="taskList"] li input[type="checkbox"] {
          width: 1rem;
          height: 1rem;
          cursor: pointer;
          accent-color: #2563eb;
        }
        
        .ProseMirror ul[data-type="taskList"] li > div {
          flex: 1;
        }
        
        .ProseMirror ul[data-type="taskList"] li[data-checked="true"] > div {
          text-decoration: line-through;
          color: #9ca3af;
        }
        
        .ProseMirror img {
          max-width: 100%;
          height: auto;
          border-radius: 0.5rem;
          margin: 1rem 0;
        }
        
        .ProseMirror h1,
        .ProseMirror h2,
        .ProseMirror h3,
        .ProseMirror h4,
        .ProseMirror h5,
        .ProseMirror h6 {
          font-weight: 700;
          margin-top: 1.5rem;
          margin-bottom: 0.75rem;
          line-height: 1.25;
        }
        
        .ProseMirror h1 { font-size: 2rem; }
        .ProseMirror h2 { font-size: 1.5rem; }
        .ProseMirror h3 { font-size: 1.25rem; }
        .ProseMirror h4 { font-size: 1.125rem; }
        .ProseMirror h5 { font-size: 1rem; }
        .ProseMirror h6 { font-size: 0.875rem; }
        
        .ProseMirror p {
          margin: 0.75rem 0;
          line-height: 1.75;
        }
        
        .ProseMirror ul,
        .ProseMirror ol {
          padding-left: 1.5rem;
          margin: 0.75rem 0;
        }
        
        .ProseMirror li {
          margin: 0.25rem 0;
        }
        
        .ProseMirror a {
          color: #2563eb;
          text-decoration: underline;
        }
        
        .ProseMirror a:hover {
          color: #1d4ed8;
        }
        
        .collaboration-cursor__caret {
          position: relative;
          margin-left: -1px;
          margin-right: -1px;
          border-left: 1px solid currentColor;
          border-right: 1px solid currentColor;
          word-break: normal;
        }
        
        .collaboration-cursor__label {
          position: absolute;
          top: -1.5em;
          left: -1px;
          font-size: 0.75rem;
          font-weight: 500;
          color: white;
          padding: 0.125rem 0.375rem;
          border-radius: 0.25rem;
          white-space: nowrap;
          pointer-events: none;
        }
      `}</style>
    </div>
  );
}
