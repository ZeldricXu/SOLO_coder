import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { useAppStore } from '../stores/appStore';
import { useAutoSave } from '../hooks/useAutoSave';
import { EditorCore, type EditorCoreRef, type EditorModeType } from '../components/editor/EditorCore';
import { PreviewRenderer, type PreviewRendererRef } from '../components/editor/PreviewRenderer';
import { SyncManager, useSyncManager } from '../components/editor/SyncManager';
import { EditorToolbar } from '../components/editor/EditorToolbar';
import { type EditorConfig } from '@core/editor';
import { getWordCount, getReadingTime, type OutlineItem } from '@core/markdown';
import type { Document, Backlink } from '@shared/types';
import { formatRelative } from '@shared/utils/date';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import { resolveWikilinkTarget } from '@shared/utils/markdown';

export const EditorPage: React.FC = () => {
  const editorContainerRef = useRef<HTMLDivElement>(null);
  const previewRef = useRef<PreviewRendererRef>(null);
  const editorCoreRef = useRef<EditorCoreRef | null>(null);
  const editorInstanceRef = useRef<EditorCore | null>(null);

  const currentDocument = useAppStore((state) => state.currentDocument);
  const editorMode = useAppStore((state) => state.editorMode);
  const setEditorMode = useAppStore((state) => state.setEditorMode);
  const saveCurrentDocument = useAppStore((state) => state.saveCurrentDocument);
  const setCurrentDocument = useAppStore((state) => state.setCurrentDocument);
  const createDocument = useAppStore((state) => state.createDocument);
  const settings = useAppStore((state) => state.settings);
  const documents = useAppStore((state) => state.documents);
  const isLoading = useAppStore((state) => state.isLoading);

  const [content, setContent] = useState('');
  const [cursorPosition, setCursorPosition] = useState({ line: 1, column: 1 });
  const [saveStatus, setSaveStatus] = useState<'saved' | 'saving' | 'unsaved'>('saved');
  const [lastSaved, setLastSaved] = useState<Date | null>(null);
  const [showBacklinks, setShowBacklinks] = useState(true);
  const [showOutline, setShowOutline] = useState(true);
  const [showTags, setShowTags] = useState(true);
  const [showDocInfo, setShowDocInfo] = useState(true);
  const [backlinks, setBacklinks] = useState<Array<Backlink & { fromDoc?: Document }>>([]);

  const { saveNow } = useAutoSave(
    content,
    settings.autoSave,
    settings.autoSaveInterval
  );

  const syncManager = useSyncManager({ debounceMs: 10, syncTimeoutMs: 50 });

  const editorModeType: EditorModeType = typeof editorMode === 'string'
    ? editorMode as EditorModeType
    : editorMode?.type || 'split';

  const wordCount = useMemo(() => getWordCount(content), [content]);
  const readingTime = useMemo(() => getReadingTime(content), [content]);
  const lineCount = useMemo(() => content.split('\n').length, [content]);

  const outline: OutlineItem[] = useMemo(() => {
    const headingRegex = /^(#{1,6})\s+(.+)$/gm;
    const headings: Array<{ level: number; text: string; id: string; line: number }> = [];
    let match;
    while ((match = headingRegex.exec(content)) !== null) {
      const level = match[1].length;
      const text = match[2];
      const id = text
        .toLowerCase()
        .replace(/<[^>]+>/g, '')
        .replace(/[^\w\u4e00-\u9fa5]+/g, '-')
        .replace(/^-+|-+$/g, '');
      headings.push({ level, text, id, line: 0 });
    }
    return headings;
  }, [content]);

  const loadBacklinks = useCallback(async (docId: string) => {
    try {
      const response = await window.electron.ipc.invoke<
        | { success: true; data: Array<Backlink & { fromDoc: Document }> }
        | { success: false; error: string }
      >(IPC_CHANNELS.DB.BACKLINK_GET_TO, docId);
      if (response.success) {
        setBacklinks(response.data);
      }
    } catch (error) {
      console.error('加载反向链接失败:', error);
    }
  }, []);

  const handleWikilinkClick = useCallback(async (target: string) => {
    const resolved = resolveWikilinkTarget(target, documents);
    if (resolved) {
      await setCurrentDocument(resolved.id);
    } else {
      const doc = await createDocument(target, '', []);
      if (doc) {
        await setCurrentDocument(doc.id);
      }
    }
  }, [documents, setCurrentDocument, createDocument]);

  const handleContentChange = useCallback((newContent: string) => {
    setContent(newContent);
    setSaveStatus('unsaved');
  }, []);

  const handleScroll = useCallback((scrollTop: number) => {
    syncManager.syncFromEditor(scrollTop);
  }, [syncManager]);

  const handlePreviewScroll = useCallback((e: React.UIEvent<HTMLDivElement>) => {
    syncManager.syncFromPreview(e.currentTarget.scrollTop);
  }, [syncManager]);

  const handleCursorChange = useCallback((pos: { line: number; column: number }) => {
    setCursorPosition(pos);
  }, []);

  const handleSave = useCallback(async () => {
    if (!currentDocument || content === currentDocument.content) return;

    setSaveStatus('saving');
    try {
      await saveCurrentDocument(content);
      setSaveStatus('saved');
      setLastSaved(new Date());
      if (currentDocument) {
        await loadBacklinks(currentDocument.id);
      }
    } catch (error) {
      setSaveStatus('unsaved');
    }
  }, [content, currentDocument, saveCurrentDocument, loadBacklinks]);

  const handleOutlineClick = useCallback((item: OutlineItem) => {
    if (!editorCoreRef.current) return;
    const lines = content.split('\n');
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].trim().startsWith('#'.repeat(item.level) + ' ' + item.text)) {
        editorCoreRef.current.scrollToLine(i + 1);
        break;
      }
    }
  }, [content]);

  const handleModeChange = useCallback((mode: EditorModeType) => {
    setEditorMode({ type: mode, ratio: 0.5 });

    if (mode === 'wysiwyg') {
      editorCoreRef.current?.setWysiwyg(true);
    } else {
      editorCoreRef.current?.setWysiwyg(false);
    }

    syncManager.setEnabled(mode === 'split');
  }, [setEditorMode, syncManager]);

  useEffect(() => {
    if (!editorContainerRef.current) return;

    const editorConfig: EditorConfig = {
      theme: settings.editorTheme,
      fontSize: settings.fontSize,
      lineHeight: settings.lineHeight,
      fontFamily: 'JetBrains Mono, monospace',
      showLineNumbers: settings.showLineNumbers,
      tabSize: 2,
      lineWrapping: true,
      onWikilinkClick: handleWikilinkClick,
    };

    const editorCore = new EditorCore(editorConfig);
    editorInstanceRef.current = editorCore;

    const ref = editorCore.mount(editorContainerRef.current, currentDocument?.content || '');
    editorCoreRef.current = ref;

    editorCore.setCallbacks({
      onContentChange: handleContentChange,
      onScroll: handleScroll,
      onCursorChange: handleCursorChange,
    });

    syncManager.setRefs(ref, previewRef.current);
    syncManager.setEnabled(editorModeType === 'split');

    return () => {
      syncManager.destroy();
      editorCore.destroy();
      editorCoreRef.current = null;
      editorInstanceRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!editorCoreRef.current || !currentDocument) return;

    const currentValue = editorCoreRef.current.getValue();
    if (currentValue !== currentDocument.content) {
      editorCoreRef.current.setValue(currentDocument.content || '');
      setContent(currentDocument.content || '');
      setSaveStatus('saved');
      setLastSaved(new Date(currentDocument.updatedAt));
    }
  }, [currentDocument?.id]);

  useEffect(() => {
    if (currentDocument) {
      setContent(currentDocument.content || '');
      setLastSaved(new Date(currentDocument.updatedAt));
      loadBacklinks(currentDocument.id);
    }
  }, [currentDocument?.id, currentDocument?.content, currentDocument?.updatedAt, loadBacklinks]);

  useEffect(() => {
    if (!editorCoreRef.current) return;

    if (editorModeType === 'wysiwyg') {
      editorCoreRef.current.setWysiwyg(true);
    } else {
      editorCoreRef.current.setWysiwyg(false);
    }
  }, [editorModeType]);

  if (!currentDocument) {
    return (
      <div className="h-full flex items-center justify-center text-gray-500 dark:text-gray-400">
        <div className="text-center">
          <div className="text-6xl mb-4">📄</div>
          <p>选择或创建一个文档开始编辑</p>
        </div>
      </div>
    );
  }

  if (isLoading) {
    return (
      <div className="h-full flex items-center justify-center">
        <div className="animate-spin w-8 h-8 border-2 border-blue-500 border-t-transparent rounded-full" />
      </div>
    );
  }

  return (
    <div className="h-full flex flex-col bg-white dark:bg-gray-900">
      <EditorToolbar
        editorRef={editorCoreRef.current}
        editorMode={editorModeType}
        onModeChange={handleModeChange}
        onSave={handleSave}
        saveStatus={saveStatus}
      />

      <div className="flex-1 flex overflow-hidden">
        <div className="flex-1 flex overflow-hidden">
          {editorModeType !== 'preview' && (
            <div
              ref={editorContainerRef}
              className={`h-full overflow-auto ${
                editorModeType === 'split' ? 'w-1/2 border-r border-gray-200 dark:border-gray-700' : 'w-full'
              }`}
            />
          )}

          {editorModeType !== 'source' && editorModeType !== 'wysiwyg' && (
            <PreviewRenderer
              ref={previewRef}
              content={content}
              docHash={currentDocument?.hash}
              onWikilinkClick={handleWikilinkClick}
              onScroll={handlePreviewScroll}
              className={editorModeType === 'split' ? 'w-1/2' : 'w-full'}
            />
          )}
        </div>

        <div className="w-72 border-l border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-800/50 overflow-y-auto">
          <div className="p-4 space-y-4">
            <div className="card p-4">
              <button
                onClick={() => setShowDocInfo(!showDocInfo)}
                className="w-full flex items-center justify-between text-sm font-medium text-gray-700 dark:text-gray-300"
              >
                <span>📋 文档信息</span>
                <span className="text-gray-400">{showDocInfo ? '−' : '+'}</span>
              </button>
              {showDocInfo && (
                <div className="mt-3 space-y-2 text-sm">
                  <div className="flex justify-between">
                    <span className="text-gray-500 dark:text-gray-400">字数</span>
                    <span className="font-medium">{wordCount.toLocaleString()}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-500 dark:text-gray-400">行数</span>
                    <span className="font-medium">{lineCount}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-500 dark:text-gray-400">阅读时间</span>
                    <span className="font-medium">{readingTime} 分钟</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-500 dark:text-gray-400">创建时间</span>
                    <span className="font-medium">{formatRelative(currentDocument.createdAt)}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-gray-500 dark:text-gray-400">更新时间</span>
                    <span className="font-medium">{formatRelative(currentDocument.updatedAt)}</span>
                  </div>
                </div>
              )}
            </div>

            <div className="card p-4">
              <button
                onClick={() => setShowTags(!showTags)}
                className="w-full flex items-center justify-between text-sm font-medium text-gray-700 dark:text-gray-300"
              >
                <span>🏷️ 标签</span>
                <span className="text-gray-400">{showTags ? '−' : '+'}</span>
              </button>
              {showTags && (
                <div className="mt-3">
                  {currentDocument.tags.length > 0 ? (
                    <div className="flex flex-wrap gap-1">
                      {currentDocument.tags.map((tag) => (
                        <span
                          key={tag}
                          className="badge badge-primary text-xs"
                        >
                          #{tag}
                        </span>
                      ))}
                    </div>
                  ) : (
                    <p className="text-sm text-gray-500 dark:text-gray-400">暂无标签</p>
                  )}
                </div>
              )}
            </div>

            <div className="card p-4">
              <button
                onClick={() => setShowOutline(!showOutline)}
                className="w-full flex items-center justify-between text-sm font-medium text-gray-700 dark:text-gray-300"
              >
                <span>📑 大纲</span>
                <span className="text-gray-400">{showOutline ? '−' : '+'}</span>
              </button>
              {showOutline && (
                <div className="mt-3">
                  {outline.length > 0 ? (
                    <nav className="space-y-0.5">
                      {outline.map((item, index) => (
                        <button
                          key={`${item.id}-${index}`}
                          onClick={() => handleOutlineClick(item)}
                          className="w-full text-left text-sm py-1 px-2 rounded hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors truncate"
                          style={{ paddingLeft: `${(item.level - 1) * 12 + 8}px` }}
                        >
                          {item.text}
                        </button>
                      ))}
                    </nav>
                  ) : (
                    <p className="text-sm text-gray-500 dark:text-gray-400">暂无标题</p>
                  )}
                </div>
              )}
            </div>

            <div className="card p-4">
              <button
                onClick={() => setShowBacklinks(!showBacklinks)}
                className="w-full flex items-center justify-between text-sm font-medium text-gray-700 dark:text-gray-300"
              >
                <span>🔗 反向链接 ({backlinks.length})</span>
                <span className="text-gray-400">{showBacklinks ? '−' : '+'}</span>
              </button>
              {showBacklinks && (
                <div className="mt-3">
                  {backlinks.length > 0 ? (
                    <div className="space-y-2">
                      {backlinks.map((bl) => (
                        <button
                          key={bl.id}
                          onClick={() => setCurrentDocument(bl.fromDocId)}
                          className="w-full text-left p-2 rounded hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors"
                        >
                          <div className="text-sm font-medium text-blue-500 hover:text-blue-600">
                            {bl.fromDoc?.title || '未知文档'}
                          </div>
                          <div className="text-xs text-gray-500 dark:text-gray-400 mt-1 truncate">
                            行 {bl.lineNumber}: {bl.anchorText}
                          </div>
                        </button>
                      ))}
                    </div>
                  ) : (
                    <p className="text-sm text-gray-500 dark:text-gray-400">暂无反向链接</p>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      <div className="flex items-center justify-between px-4 py-1.5 border-t border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-800/50 text-xs text-gray-500 dark:text-gray-400">
        <div className="flex items-center gap-4">
          <span>
            行 {cursorPosition.line}, 列 {cursorPosition.column}
          </span>
          <span>|</span>
          <span>{wordCount} 字</span>
          <span>|</span>
          <span>{lineCount} 行</span>
          <span>|</span>
          <span>阅读 {readingTime} 分钟</span>
        </div>
        <div className="flex items-center gap-4">
          <span
            className={`flex items-center gap-1 ${
              saveStatus === 'saved'
                ? 'text-green-500'
                : saveStatus === 'saving'
                ? 'text-yellow-500'
                : 'text-orange-500'
            }`}
          >
            <span
              className={`w-2 h-2 rounded-full ${
                saveStatus === 'saved'
                  ? 'bg-green-500'
                  : saveStatus === 'saving'
                  ? 'bg-yellow-500 animate-pulse'
                  : 'bg-orange-500'
              }`}
            />
            {saveStatus === 'saved'
              ? '已保存'
              : saveStatus === 'saving'
              ? '保存中...'
              : '未保存'}
          </span>
          {lastSaved && <span>上次保存: {formatRelative(lastSaved)}</span>}
          <span>Markdown</span>
        </div>
      </div>
    </div>
  );
};

export default EditorPage;
