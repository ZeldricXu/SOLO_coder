import React from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './styles/index.css';

declare global {
  interface Window {
    electronAPI?: {
      note: {
        create: (params: import('../shared/ipc-channels').IPCNoteCreateParams) => Promise<import('../shared/types').IPCResponse<import('../shared/types').Note>>;
        update: (params: import('../shared/ipc-channels').IPCNoteUpdateParams) => Promise<import('../shared/types').IPCResponse<import('../shared/types').Note>>;
        delete: (noteId: string) => Promise<import('../shared/types').IPCResponse>;
        get: (noteId: string) => Promise<import('../shared/types').IPCResponse<import('../shared/types').Note | null>>;
        list: (folderId?: string, limit?: number, offset?: number) => Promise<import('../shared/types').IPCResponse<import('../shared/types').Note[]>>;
        count: (folderId?: string) => Promise<import('../shared/types').IPCResponse<number>>;
      };
      folder: {
        create: (params: { name: string; parent_id?: string; order_index?: number }) => Promise<import('../shared/types').IPCResponse<import('../shared/types').Folder>>;
        update: (folderId: string, updates: Partial<import('../shared/types').Folder>) => Promise<import('../shared/types').IPCResponse<import('../shared/types').Folder>>;
        delete: (folderId: string) => Promise<import('../shared/types').IPCResponse>;
        get: (folderId: string) => Promise<import('../shared/types').IPCResponse<import('../shared/types').Folder | null>>;
        list: (parentId?: string) => Promise<import('../shared/types').IPCResponse<import('../shared/types').Folder[]>>;
      };
      tag: {
        create: (params: { name: string; color?: string }) => Promise<import('../shared/types').IPCResponse<import('../shared/types').Tag>>;
        update: (tagId: string, updates: Partial<import('../shared/types').Tag>) => Promise<import('../shared/types').IPCResponse<import('../shared/types').Tag>>;
        delete: (tagId: string) => Promise<import('../shared/types').IPCResponse>;
        get: (tagId: string) => Promise<import('../shared/types').IPCResponse<import('../shared/types').Tag | null>>;
        list: () => Promise<import('../shared/types').IPCResponse<import('../shared/types').Tag[]>>;
      };
      search: {
        query: (params: import('../shared/ipc-channels').IPCSearchParams) => Promise<import('../shared/types').IPCResponse<import('../shared/types').SearchResult[]>>;
        rebuildIndex: () => Promise<import('../shared/types').IPCResponse>;
      };
      sync: {
        start: () => Promise<import('../shared/types').IPCResponse<import('../main/services/sync').SyncResult>>;
        getStatus: () => Promise<import('../shared/types').IPCResponse<{ isSyncing: boolean; config: import('../shared/types').SyncConfig | null }>>;
        getConfig: () => Promise<import('../shared/types').IPCResponse<import('../shared/types').SyncConfig | null>>;
        setConfig: (config: import('../shared/types').SyncConfig) => Promise<import('../shared/types').IPCResponse>;
      };
      ai: {
        generateSummary: (content: string) => Promise<import('../shared/types').IPCResponse<import('../main/services/ai').SummaryResult>>;
        getConfig: () => Promise<import('../shared/types').IPCResponse<import('../shared/types').AIConfig | null>>;
        setConfig: (config: import('../shared/types').AIConfig) => Promise<import('../shared/types').IPCResponse>;
      };
      settings: {
        get: () => Promise<import('../shared/types').IPCResponse<import('../shared/types').AppSettings>>;
        set: (settings: Partial<import('../shared/types').AppSettings>) => Promise<import('../shared/types').IPCResponse>;
      };
      app: {
        quit: () => Promise<void>;
        minimize: () => Promise<void>;
        maximize: () => Promise<void>;
      };
      window: {
        getBounds: () => Promise<{ x: number; y: number; width: number; height: number } | null>;
        resize: (width: number, height: number) => Promise<void>;
      };
    };
  }
}

const rootElement = document.getElementById('root');
if (!rootElement) {
  throw new Error('Could not find root element');
}

const root = createRoot(rootElement);
root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
