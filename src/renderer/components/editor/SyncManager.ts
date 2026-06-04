import type { EditorCoreRef } from './EditorCore';
import type { PreviewRendererRef } from './PreviewRenderer';

export interface SyncManagerOptions {
  debounceMs?: number;
  syncTimeoutMs?: number;
}

export type SyncDirection = 'editor-to-preview' | 'preview-to-editor';

export class SyncManager {
  private editorRef: EditorCoreRef | null = null;
  private previewRef: PreviewRendererRef | null = null;
  private isSyncing = false;
  private scrollTimeout: NodeJS.Timeout | null = null;
  private debounceMs: number;
  private syncTimeoutMs: number;
  private enabled = true;
  private syncDirection: SyncDirection = 'editor-to-preview';

  constructor(options: SyncManagerOptions = {}) {
    this.debounceMs = options.debounceMs ?? 10;
    this.syncTimeoutMs = options.syncTimeoutMs ?? 50;
  }

  setRefs(editorRef: EditorCoreRef | null, previewRef: PreviewRendererRef | null): void {
    this.editorRef = editorRef;
    this.previewRef = previewRef;
  }

  setEnabled(enabled: boolean): void {
    this.enabled = enabled;
  }

  setSyncDirection(direction: SyncDirection): void {
    this.syncDirection = direction;
  }

  syncFromEditor(scrollTop: number): void {
    if (!this.enabled || this.isSyncing || this.syncDirection !== 'editor-to-preview') return;
    if (!this.editorRef || !this.previewRef) return;

    if (this.scrollTimeout) {
      clearTimeout(this.scrollTimeout);
    }

    this.scrollTimeout = setTimeout(() => {
      if (!this.editorRef || !this.previewRef) return;

      const editorScrollDom = this.editorRef.getScrollDom();
      if (!editorScrollDom) return;

      const editorScrollHeight = editorScrollDom.scrollHeight - editorScrollDom.clientHeight;
      const scrollRatio = editorScrollHeight > 0 ? scrollTop / editorScrollHeight : 0;

      const previewScrollHeight = this.previewRef.getScrollHeight() - (this.previewRef.getElement()?.clientHeight || 0);
      const previewScrollTop = scrollRatio * previewScrollHeight;

      this.isSyncing = true;
      this.previewRef.scrollTo(previewScrollTop);

      setTimeout(() => {
        this.isSyncing = false;
      }, this.syncTimeoutMs);
    }, this.debounceMs);
  }

  syncFromPreview(previewScrollTop: number): void {
    if (!this.enabled || this.isSyncing || this.syncDirection !== 'preview-to-editor') return;
    if (!this.editorRef || !this.previewRef) return;

    if (this.scrollTimeout) {
      clearTimeout(this.scrollTimeout);
    }

    this.scrollTimeout = setTimeout(() => {
      if (!this.editorRef || !this.previewRef) return;

      const previewScrollHeight = this.previewRef.getScrollHeight() - (this.previewRef.getElement()?.clientHeight || 0);
      const scrollRatio = previewScrollHeight > 0 ? previewScrollTop / previewScrollHeight : 0;

      const editorScrollDom = this.editorRef.getScrollDom();
      if (!editorScrollDom) return;

      const editorScrollHeight = editorScrollDom.scrollHeight - editorScrollDom.clientHeight;
      const editorScrollTop = scrollRatio * editorScrollHeight;

      this.isSyncing = true;
      editorScrollDom.scrollTop = editorScrollTop;

      setTimeout(() => {
        this.isSyncing = false;
      }, this.syncTimeoutMs);
    }, this.debounceMs);
  }

  syncToLine(lineNumber: number, totalLines: number): void {
    if (!this.enabled || !this.previewRef) return;

    const scrollRatio = totalLines > 0 ? (lineNumber - 1) / totalLines : 0;
    const previewScrollHeight = this.previewRef.getScrollHeight() - (this.previewRef.getElement()?.clientHeight || 0);
    const previewScrollTop = scrollRatio * previewScrollHeight;

    this.previewRef.scrollTo(previewScrollTop);
  }

  getIsSyncing(): boolean {
    return this.isSyncing;
  }

  destroy(): void {
    if (this.scrollTimeout) {
      clearTimeout(this.scrollTimeout);
      this.scrollTimeout = null;
    }
    this.editorRef = null;
    this.previewRef = null;
  }
}

export const useSyncManager = (options: SyncManagerOptions = {}) => {
  const syncManagerRef = React.useRef<SyncManager | null>(null);

  if (!syncManagerRef.current) {
    syncManagerRef.current = new SyncManager(options);
  }

  React.useEffect(() => {
    return () => {
      syncManagerRef.current?.destroy();
      syncManagerRef.current = null;
    };
  }, []);

  return syncManagerRef.current;
};

import React from 'react';
