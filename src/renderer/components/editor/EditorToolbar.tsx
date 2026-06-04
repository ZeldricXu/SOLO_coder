import React, { useCallback, useEffect } from 'react';
import { EditorState } from '@codemirror/state';
import {
  toggleBold,
  toggleItalic,
  toggleStrikethrough,
  toggleCode,
  wrapInBlockquote,
  toggleHeadingLevel,
  insertCodeBlock,
  insertLink,
  insertImage,
  insertList,
  insertTable,
  insertHorizontalRule,
  insertTaskList,
  createWikilink,
} from '@core/editor';
import type { EditorCoreRef } from './EditorCore';
import type { EditorModeType } from './EditorCore';

export interface ToolbarButtonProps {
  icon: string;
  title: string;
  onClick: () => void;
  active?: boolean;
  disabled?: boolean;
}

export const ToolbarButton: React.FC<ToolbarButtonProps> = ({
  icon,
  title,
  onClick,
  active = false,
  disabled = false,
}) => (
  <button
    onClick={onClick}
    title={title}
    disabled={disabled}
    className={`w-8 h-8 flex items-center justify-center rounded transition-colors ${
      active
        ? 'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400'
        : disabled
        ? 'opacity-50 cursor-not-allowed'
        : 'hover:bg-gray-100 dark:hover:bg-gray-700 text-gray-600 dark:text-gray-300'
    }`}
  >
    <span className="text-base">{icon}</span>
  </button>
);

export interface EditorToolbarProps {
  editorRef: EditorCoreRef | null;
  editorMode: EditorModeType;
  onModeChange: (mode: EditorModeType) => void;
  onSave: () => void;
  saveStatus: 'saved' | 'saving' | 'unsaved';
}

export interface EditorToolbarActions {
  execCommand: (command: (state: EditorState, dispatch: any) => boolean) => void;
}

export const EditorToolbar: React.FC<EditorToolbarProps> = ({
  editorRef,
  editorMode,
  onModeChange,
  onSave,
  saveStatus,
}) => {
  const execCommand = useCallback(
    (command: (state: EditorState, dispatch: any) => boolean) => {
      if (!editorRef) return;
      editorRef.execCommand(command);
    },
    [editorRef]
  );

  const handleInsertWikilink = useCallback(() => {
    if (!editorRef?.view) return;
    const link = createWikilink(editorRef.view.state, '');
    editorRef.insertAtCursor(link);
  }, [editorRef]);

  const modeButtons = [
    { mode: 'source' as EditorModeType, icon: '📝', label: '源码' },
    { mode: 'split' as EditorModeType, icon: '⬛⬛', label: '分屏' },
    { mode: 'preview' as EditorModeType, icon: '👁️', label: '预览' },
    { mode: 'wysiwyg' as EditorModeType, icon: '✨', label: '所见即所得' },
  ];

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'b') {
        e.preventDefault();
        execCommand(toggleBold);
      } else if ((e.ctrlKey || e.metaKey) && e.key === 'i') {
        e.preventDefault();
        execCommand(toggleItalic);
      } else if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        onSave();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [execCommand, onSave]);

  const Divider = () => (
    <div className="w-px h-6 bg-gray-300 dark:bg-gray-600 mx-1" />
  );

  return (
    <div className="flex items-center justify-between px-4 py-2 border-b border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-800/50">
      <div className="flex items-center gap-1">
        <ToolbarButton
          icon="𝐁"
          title="加粗 (Ctrl+B)"
          onClick={() => execCommand(toggleBold)}
        />
        <ToolbarButton
          icon="𝐼"
          title="斜体 (Ctrl+I)"
          onClick={() => execCommand(toggleItalic)}
        />
        <ToolbarButton
          icon="S̶"
          title="删除线"
          onClick={() => execCommand(toggleStrikethrough)}
        />
        <ToolbarButton
          icon="`"
          title="行内代码"
          onClick={() => execCommand(toggleCode)}
        />
        <Divider />
        <ToolbarButton
          icon="❝"
          title="引用"
          onClick={() => execCommand(wrapInBlockquote)}
        />
        <ToolbarButton
          icon="H1"
          title="标题1"
          onClick={() => execCommand((s, d) => toggleHeadingLevel(s, d, 1))}
        />
        <ToolbarButton
          icon="H2"
          title="标题2"
          onClick={() => execCommand((s, d) => toggleHeadingLevel(s, d, 2))}
        />
        <ToolbarButton
          icon="H3"
          title="标题3"
          onClick={() => execCommand((s, d) => toggleHeadingLevel(s, d, 3))}
        />
        <ToolbarButton
          icon="H4"
          title="标题4"
          onClick={() => execCommand((s, d) => toggleHeadingLevel(s, d, 4))}
        />
        <Divider />
        <ToolbarButton
          icon="⌨️"
          title="代码块"
          onClick={() => execCommand(insertCodeBlock)}
        />
        <ToolbarButton
          icon="🔗"
          title="链接"
          onClick={() => execCommand(insertLink)}
        />
        <ToolbarButton
          icon="🖼️"
          title="图片"
          onClick={() => execCommand(insertImage)}
        />
        <ToolbarButton
          icon="🔗"
          title="双向链接"
          onClick={handleInsertWikilink}
        />
        <Divider />
        <ToolbarButton
          icon="•"
          title="无序列表"
          onClick={() => execCommand((s, d) => insertList(s, d, 'unordered'))}
        />
        <ToolbarButton
          icon="1."
          title="有序列表"
          onClick={() => execCommand((s, d) => insertList(s, d, 'ordered'))}
        />
        <ToolbarButton
          icon="☑"
          title="任务列表"
          onClick={() => execCommand(insertTaskList)}
        />
        <ToolbarButton
          icon="⊞"
          title="表格"
          onClick={() => execCommand(insertTable)}
        />
        <ToolbarButton
          icon="―"
          title="分割线"
          onClick={() => execCommand(insertHorizontalRule)}
        />
      </div>

      <div className="flex items-center gap-2">
        <div className="flex bg-gray-200 dark:bg-gray-700 rounded p-0.5">
          {modeButtons.map(({ mode, icon, label }) => (
            <button
              key={mode}
              onClick={() => onModeChange(mode)}
              title={label}
              className={`px-2 py-1 rounded text-xs transition-colors ${
                editorMode === mode
                  ? 'bg-white dark:bg-gray-600 text-gray-900 dark:text-white shadow-sm'
                  : 'text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-white'
              }`}
            >
              {icon}
            </button>
          ))}
        </div>
        <button
          onClick={onSave}
          disabled={saveStatus === 'saving'}
          className="px-3 py-1.5 text-sm bg-blue-500 text-white rounded hover:bg-blue-600 disabled:opacity-50 transition-colors"
        >
          {saveStatus === 'saving' ? '保存中...' : '保存'}
        </button>
      </div>
    </div>
  );
};

export default EditorToolbar;
