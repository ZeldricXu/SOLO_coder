import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { createEditor, Transforms, Editor, Node } from 'slate';
import { Slate, Editable, withReact } from 'slate-react';
import { withHistory } from 'slate-history';
import { Bold, Italic, Code, List, ListOrdered, Quote, Heading1, Heading2, Heading3, Save, Users, MessageSquare, Clock, Eye, FileDown } from 'lucide-react';
import collaborationClient from '../lib/collaboration';
import { documentApi, exportApi } from '../lib/api';

const InitialValue = [
  {
    type: 'paragraph',
    children: [{ text: '' }]
  }
];

const MARKDOWN_SHORTCUTS = {
  '# ': 'heading-one',
  '## ': 'heading-two',
  '### ': 'heading-three',
  '- ': 'list-item',
  '* ': 'list-item',
  '+ ': 'list-item',
  '1. ': 'numbered-list',
  '> ': 'block-quote',
  '`': 'code',
  '**': 'bold',
  '__': 'bold',
  '*': 'italic',
  '_': 'italic'
};

function withMarkdown(editor) {
  const { deleteBackward, insertText } = editor;

  editor.insertText = (text) => {
    const { selection } = editor;

    if (text === ' ' && selection && selection.anchor.offset === selection.focus.offset) {
      const { anchor } = selection;
      const block = editor.above({
        at: anchor.path.slice(0, 1),
        match: (n) => n.type !== 'text'
      });

      if (block) {
        const [node] = block;
        const start = editor.start(anchor.path.slice(0, 1));
        const before = editor.string({
          anchor: start,
          focus: anchor
        });

        for (const [shortcut, type] of Object.entries(MARKDOWN_SHORTCUTS)) {
          if (before.endsWith(shortcut)) {
            editor.deleteBackward('character');
            editor.deleteBackward('character');

            if (type === 'list-item' || type === 'numbered-list') {
              Transforms.setNodes(
                editor,
                { type: 'list-item' },
                { match: (n) => n.type === 'paragraph' }
              );

              if (type === 'numbered-list') {
                Transforms.wrapNodes(
                  editor,
                  { type: 'numbered-list', children: [] },
                  { match: (n) => n.type === 'list-item' }
                );
              } else {
                Transforms.wrapNodes(
                  editor,
                  { type: 'bulleted-list', children: [] },
                  { match: (n) => n.type === 'list-item' }
                );
              }
            } else if (type.startsWith('heading')) {
              Transforms.setNodes(
                editor,
                { type },
                { match: (n) => n.type === 'paragraph' }
              );
            } else if (type === 'block-quote') {
              Transforms.wrapNodes(
                editor,
                { type: 'block-quote', children: [] },
                { match: (n) => n.type === 'paragraph' }
              );
            }

            return;
          }
        }
      }
    }

    insertText(text);
  };

  return editor;
}

function ToolbarButton({ active, onMouseDown, icon: Icon, title }) {
  return (
    <button
      onMouseDown={onMouseDown}
      className={`p-2 rounded hover:bg-slate-100 transition-colors ${
        active ? 'bg-slate-200 text-primary-600' : 'text-slate-600'
      }`}
      title={title}
    >
      <Icon size={18} />
    </button>
  );
}

function Toolbar({ editor, onSave, onTogglePreview, isPreviewMode, document, collaborators, onExport }) {
  const isBlockActive = (format) => {
    const { selection } = editor;
    if (!selection) return false;

    const [match] = Array.from(
      Editor.nodes(editor, {
        at: Editor.unhangRange(editor, selection),
        match: (n) => n.type === format
      })
    );

    return !!match;
  };

  const isMarkActive = (format) => {
    const marks = Editor.marks(editor);
    return marks ? marks[format] === true : false;
  };

  const toggleBlock = (format) => {
    const isActive = isBlockActive(format);
    const isList = format === 'bulleted-list' || format === 'numbered-list';

    Transforms.unwrapNodes(editor, {
      match: (n) =>
        ['bulleted-list', 'numbered-list'].includes(n.type),
      split: true
    });

    Transforms.setNodes(
      editor,
      {
        type: isActive ? 'paragraph' : isList ? 'list-item' : format
      },
      { match: (n) => Editor.isBlock(editor, n) }
    );

    if (!isActive && isList) {
      const block = { type: format, children: [] };
      Transforms.wrapNodes(editor, block);
    }
  };

  const toggleMark = (format) => {
    const isActive = isMarkActive(format);

    if (isActive) {
      Editor.removeMark(editor, format);
    } else {
      Editor.addMark(editor, format, true);
    }
  };

  return (
    <div className="flex items-center justify-between px-4 py-2 border-b border-slate-200 bg-white">
      <div className="flex items-center gap-1">
        <ToolbarButton
          active={isMarkActive('bold')}
          onMouseDown={(e) => {
            e.preventDefault();
            toggleMark('bold');
          }}
          icon={Bold}
          title="加粗 (Ctrl+B)"
        />
        <ToolbarButton
          active={isMarkActive('italic')}
          onMouseDown={(e) => {
            e.preventDefault();
            toggleMark('italic');
          }}
          icon={Italic}
          title="斜体 (Ctrl+I)"
        />
        <ToolbarButton
          active={isMarkActive('code')}
          onMouseDown={(e) => {
            e.preventDefault();
            toggleMark('code');
          }}
          icon={Code}
          title="代码"
        />
        
        <div className="w-px h-6 bg-slate-200 mx-1" />
        
        <ToolbarButton
          active={isBlockActive('heading-one')}
          onMouseDown={(e) => {
            e.preventDefault();
            toggleBlock('heading-one');
          }}
          icon={Heading1}
          title="标题 1"
        />
        <ToolbarButton
          active={isBlockActive('heading-two')}
          onMouseDown={(e) => {
            e.preventDefault();
            toggleBlock('heading-two');
          }}
          icon={Heading2}
          title="标题 2"
        />
        <ToolbarButton
          active={isBlockActive('heading-three')}
          onMouseDown={(e) => {
            e.preventDefault();
            toggleBlock('heading-three');
          }}
          icon={Heading3}
          title="标题 3"
        />
        
        <div className="w-px h-6 bg-slate-200 mx-1" />
        
        <ToolbarButton
          active={isBlockActive('bulleted-list')}
          onMouseDown={(e) => {
            e.preventDefault();
            toggleBlock('bulleted-list');
          }}
          icon={List}
          title="无序列表"
        />
        <ToolbarButton
          active={isBlockActive('numbered-list')}
          onMouseDown={(e) => {
            e.preventDefault();
            toggleBlock('numbered-list');
          }}
          icon={ListOrdered}
          title="有序列表"
        />
        <ToolbarButton
          active={isBlockActive('block-quote')}
          onMouseDown={(e) => {
            e.preventDefault();
            toggleBlock('block-quote');
          }}
          icon={Quote}
          title="引用"
        />
      </div>

      <div className="flex items-center gap-2">
        <button
          onClick={onTogglePreview}
          className={`flex items-center gap-1 px-3 py-1.5 rounded text-sm transition-colors ${
            isPreviewMode
              ? 'bg-primary-100 text-primary-700'
              : 'text-slate-600 hover:bg-slate-100'
          }`}
          title="预览模式"
        >
          <Eye size={16} />
          <span>预览</span>
        </button>

        <button
          onClick={onExport}
          className="flex items-center gap-1 px-3 py-1.5 rounded text-sm text-slate-600 hover:bg-slate-100 transition-colors"
          title="导出"
        >
          <FileDown size={16} />
          <span>导出</span>
        </button>

        {collaborators && collaborators.length > 0 && (
          <div className="flex items-center gap-1 px-3 py-1.5 rounded text-sm text-slate-600 bg-slate-50">
            <Users size={16} />
            <span>{collaborators.length}</span>
          </div>
        )}

        <button
          onClick={onSave}
          className="flex items-center gap-1.5 px-4 py-1.5 rounded text-sm bg-primary-600 text-white hover:bg-primary-700 transition-colors"
          title="保存 (Ctrl+S)"
        >
          <Save size={16} />
          <span>保存</span>
        </button>
      </div>
    </div>
  );
}

function Element({ attributes, children, element }) {
  switch (element.type) {
    case 'heading-one':
      return (
        <h1 {...attributes} className="text-3xl font-bold mt-6 mb-4 text-slate-900">
          {children}
        </h1>
      );
    case 'heading-two':
      return (
        <h2 {...attributes} className="text-2xl font-semibold mt-5 mb-3 text-slate-800">
          {children}
        </h2>
      );
    case 'heading-three':
      return (
        <h3 {...attributes} className="text-xl font-semibold mt-4 mb-2 text-slate-800">
          {children}
        </h3>
      );
    case 'block-quote':
      return (
        <blockquote
          {...attributes}
          className="border-l-4 border-primary-500 pl-4 my-4 text-slate-600 italic"
        >
          {children}
        </blockquote>
      );
    case 'bulleted-list':
      return (
        <ul {...attributes} className="list-disc ml-6 my-2 text-slate-700">
          {children}
        </ul>
      );
    case 'numbered-list':
      return (
        <ol {...attributes} className="list-decimal ml-6 my-2 text-slate-700">
          {children}
        </ol>
      );
    case 'list-item':
      return (
        <li {...attributes} className="my-1">
          {children}
        </li>
      );
    case 'code-block':
      return (
        <pre
          {...attributes}
          className="bg-slate-900 text-slate-100 p-4 rounded-lg my-4 overflow-x-auto font-mono text-sm"
        >
          {children}
        </pre>
      );
    default:
      return (
        <p {...attributes} className="my-2 text-slate-700 leading-relaxed">
          {children}
        </p>
      );
  }
}

function Leaf({ attributes, children, leaf }) {
  if (leaf.bold) {
    children = <strong>{children}</strong>;
  }

  if (leaf.italic) {
    children = <em>{children}</em>;
  }

  if (leaf.underline) {
    children = <u>{children}</u>;
  }

  if (leaf.code) {
    children = (
      <code className="bg-slate-100 text-red-600 px-1.5 py-0.5 rounded text-sm font-mono">
        {children}
      </code>
    );
  }

  if (leaf.strikethrough) {
    children = <del>{children}</del>;
  }

  return <span {...attributes}>{children}</span>;
}

function DocumentEditor({ document: doc, onContentChange, userId, userName }) {
  const [editor] = useState(() => withMarkdown(withHistory(withReact(createEditor()))));
  const [isPreviewMode, setIsPreviewMode] = useState(false);
  const [collaborators, setCollaborators] = useState([]);
  const [exportMenuOpen, setExportMenuOpen] = useState(false);
  const exportRef = useRef(null);

  const initialValue = useMemo(() => {
    if (!doc || !doc.content) {
      return InitialValue;
    }
    
    try {
      return JSON.parse(doc.content);
    } catch {
      return [
        {
          type: 'paragraph',
          children: [{ text: doc.content || '' }]
        }
      ];
    }
  }, [doc?.doc_id]);

  useEffect(() => {
    if (!doc || !userId) return;

    collaborationClient.joinDocument(doc.doc_id, userId, userName);

    const unsub1 = collaborationClient.on('onCollaboratorJoin', (data) => {
      setCollaborators(data.collaborators || []);
    });

    const unsub2 = collaborationClient.on('onCollaboratorLeave', (data) => {
      setCollaborators(data.collaborators || []);
    });

    const unsub3 = collaborationClient.on('onSync', (data) => {
      if (data.op_type === 'replace' && data.op_data.content) {
        try {
          const newContent = JSON.parse(data.op_data.content);
          Transforms.insertNodes(editor, newContent, { at: [0] });
        } catch (e) {
          console.error('Failed to apply sync:', e);
        }
      }
    });

    setCollaborators(collaborationClient.getCollaborators());

    return () => {
      unsub1();
      unsub2();
      unsub3();
      collaborationClient.leaveDocument();
    };
  }, [doc?.doc_id, userId, userName, editor]);

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (exportRef.current && !exportRef.current.contains(event.target)) {
        setExportMenuOpen(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleSave = useCallback(async () => {
    if (!doc) return;

    const content = JSON.stringify(editor.children);
    const title = getDocumentTitle(editor);

    try {
      await documentApi.update(doc.doc_id, {
        content,
        title,
        last_edited_by: userId
      });

      collaborationClient.sendReplace(content, title);
    } catch (error) {
      console.error('Failed to save document:', error);
    }
  }, [doc, editor, userId]);

  const getDocumentTitle = (ed) => {
    if (!ed.children || ed.children.length === 0) return 'Untitled Document';
    
    const firstChild = ed.children[0];
    if (firstChild.type === 'heading-one') {
      return Node.string(firstChild) || 'Untitled Document';
    }
    
    return 'Untitled Document';
  };

  const handleExport = useCallback(async (format) => {
    if (!doc) return;
    
    try {
      let blob;
      let filename;
      
      if (format === 'pdf') {
        blob = await exportApi.exportPDF(doc.doc_id);
        filename = `${doc.title || 'document'}.pdf`;
      } else {
        blob = await exportApi.exportHTML(doc.doc_id);
        filename = `${doc.title || 'document'}.html`;
      }
      
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      
    } catch (error) {
      console.error('Export failed:', error);
    }
    
    setExportMenuOpen(false);
  }, [doc]);

  const handleTogglePreview = useCallback(() => {
    setIsPreviewMode(!isPreviewMode);
  }, [isPreviewMode]);

  const handleChange = useCallback((value) => {
    if (onContentChange) {
      onContentChange(value);
    }
  }, [onContentChange]);

  useEffect(() => {
    const handleKeyDown = (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        handleSave();
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [handleSave]);

  if (!doc) {
    return (
      <div className="flex items-center justify-center h-full text-slate-500">
        选择一个文档开始编辑
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full bg-white">
      <Toolbar
        editor={editor}
        onSave={handleSave}
        onTogglePreview={handleTogglePreview}
        isPreviewMode={isPreviewMode}
        document={doc}
        collaborators={collaborators.filter(c => c.user_id !== userId)}
        onExport={() => setExportMenuOpen(!exportMenuOpen)}
      />

      {exportMenuOpen && (
        <div
          ref={exportRef}
          className="absolute right-4 top-14 bg-white border border-slate-200 rounded-lg shadow-lg z-50 py-2 min-w-[160px]"
        >
          <button
            onClick={() => handleExport('pdf')}
            className="w-full px-4 py-2 text-left text-sm text-slate-700 hover:bg-slate-50 flex items-center gap-2"
          >
            <FileDown size={16} />
            导出为 PDF
          </button>
          <button
            onClick={() => handleExport('html')}
            className="w-full px-4 py-2 text-left text-sm text-slate-700 hover:bg-slate-50 flex items-center gap-2"
          >
            <FileDown size={16} />
            导出为 HTML
          </button>
        </div>
      )}

      <div className="flex-1 overflow-auto">
        {isPreviewMode ? (
          <div className="max-w-4xl mx-auto px-8 py-8">
            <h1 className="text-3xl font-bold mb-6 text-slate-900 border-b pb-4">
              {doc.title}
            </h1>
            <div className="markdown-preview">
              {editor.children.map((node, index) => (
                <Element
                  key={index}
                  attributes={{}}
                  element={node}
                >
                  {node.children.map((leaf, i) => (
                    <Leaf key={i} attributes={{}} leaf={leaf}>
                      {leaf.text}
                    </Leaf>
                  ))}
                </Element>
              ))}
            </div>
          </div>
        ) : (
          <Slate
            editor={editor}
            value={initialValue}
            onChange={handleChange}
          >
            <div className="max-w-4xl mx-auto px-8 py-8">
              <Editable
                className="slate-editor min-h-[500px] outline-none"
                renderElement={(props) => <Element {...props} />}
                renderLeaf={(props) => <Leaf {...props} />}
                placeholder="开始输入内容... (使用 # 创建标题, * 创建列表, > 创建引用)"
              />
            </div>
          </Slate>
        )}
      </div>

      {collaborators.filter(c => c.user_id !== userId).length > 0 && (
        <div className="border-t border-slate-200 px-4 py-2 bg-slate-50 flex items-center gap-2">
          <span className="text-sm text-slate-500">协作者:</span>
          {collaborators.filter(c => c.user_id !== userId).map((collab, index) => (
            <div
              key={collab.user_id}
              className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-primary-100 text-primary-700 text-xs font-medium"
            >
              <div
                className="w-2 h-2 rounded-full bg-primary-500"
                style={{
                  backgroundColor: `hsl(${(index * 60) % 360}, 70%, 50%)`
                }}
              />
              {collab.user_name}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default DocumentEditor;
