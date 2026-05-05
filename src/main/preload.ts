import { contextBridge, ipcRenderer } from 'electron';
import { IPC_CHANNELS, IPCNoteCreateParams, IPCNoteUpdateParams, IPCSearchParams, IPCSyncConfig, IPCAIConfig, IPCSyncConflict, IPCConflictResolution } from '../shared/ipc-channels';
import { Note, Folder, Tag, SearchResult, AppSettings, SyncConfig, AIConfig, IPCResponse } from '../shared/types';
import { SyncResult } from './services/sync';
import { SummaryResult } from './services/ai';

const api = {
  note: {
    create: (params: IPCNoteCreateParams): Promise<IPCResponse<Note>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.NOTE_CREATE, params);
    },
    update: (params: IPCNoteUpdateParams): Promise<IPCResponse<Note>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.NOTE_UPDATE, params);
    },
    delete: (noteId: string): Promise<IPCResponse> => {
      return ipcRenderer.invoke(IPC_CHANNELS.NOTE_DELETE, noteId);
    },
    get: (noteId: string): Promise<IPCResponse<Note | null>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.NOTE_GET, noteId);
    },
    list: (folderId?: string, limit?: number, offset?: number): Promise<IPCResponse<Note[]>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.NOTE_LIST, folderId, limit, offset);
    },
    count: (folderId?: string): Promise<IPCResponse<number>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.NOTE_COUNT, folderId);
    },
  },

  folder: {
    create: (params: { name: string; parent_id?: string; order_index?: number }): Promise<IPCResponse<Folder>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.FOLDER_CREATE, params);
    },
    update: (folderId: string, updates: Partial<Folder>): Promise<IPCResponse<Folder>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.FOLDER_UPDATE, folderId, updates);
    },
    delete: (folderId: string): Promise<IPCResponse> => {
      return ipcRenderer.invoke(IPC_CHANNELS.FOLDER_DELETE, folderId);
    },
    get: (folderId: string): Promise<IPCResponse<Folder | null>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.FOLDER_GET, folderId);
    },
    list: (parentId?: string): Promise<IPCResponse<Folder[]>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.FOLDER_LIST, parentId);
    },
  },

  tag: {
    create: (params: { name: string; color?: string }): Promise<IPCResponse<Tag>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.TAG_CREATE, params);
    },
    update: (tagId: string, updates: Partial<Tag>): Promise<IPCResponse<Tag>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.TAG_UPDATE, tagId, updates);
    },
    delete: (tagId: string): Promise<IPCResponse> => {
      return ipcRenderer.invoke(IPC_CHANNELS.TAG_DELETE, tagId);
    },
    get: (tagId: string): Promise<IPCResponse<Tag | null>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.TAG_GET, tagId);
    },
    list: (): Promise<IPCResponse<Tag[]>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.TAG_LIST);
    },
  },

  search: {
    query: (params: IPCSearchParams): Promise<IPCResponse<SearchResult[]>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.SEARCH_QUERY, params);
    },
    rebuildIndex: (): Promise<IPCResponse> => {
      return ipcRenderer.invoke(IPC_CHANNELS.SEARCH_REBUILD_INDEX);
    },
  },

  sync: {
    start: (): Promise<IPCResponse<SyncResult>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.SYNC_START);
    },
    getStatus: (): Promise<IPCResponse<{ isSyncing: boolean; config: SyncConfig | null; conflictCount: number }>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.SYNC_STATUS);
    },
    getConfig: (): Promise<IPCResponse<SyncConfig | null>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.SYNC_CONFIG_GET);
    },
    setConfig: (config: IPCSyncConfig): Promise<IPCResponse<boolean>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.SYNC_CONFIG_SET, config);
    },
    clearConfig: (): Promise<IPCResponse<boolean>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.SYNC_CONFIG_CLEAR);
    },
    getConflicts: (): Promise<IPCResponse<IPCSyncConflict[]>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.SYNC_CONFLICTS_GET);
    },
    resolveConflict: (noteId: string, resolution: IPCConflictResolution): Promise<IPCResponse> => {
      return ipcRenderer.invoke(IPC_CHANNELS.SYNC_CONFLICT_RESOLVE, noteId, resolution);
    },
    clearAllConflicts: (): Promise<IPCResponse<boolean>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.SYNC_CONFLICTS_CLEAR);
    },
  },

  ai: {
    generateSummary: (content: string): Promise<IPCResponse<SummaryResult>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.AI_SUMMARY_GENERATE, content);
    },
    getConfig: (): Promise<IPCResponse<AIConfig | null>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.AI_CONFIG_GET);
    },
    setConfig: (config: IPCAIConfig): Promise<IPCResponse<boolean>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.AI_CONFIG_SET, config);
    },
    clearConfig: (): Promise<IPCResponse<boolean>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.AI_CONFIG_CLEAR);
    },
  },

  secureStorage: {
    getStatus: (): Promise<IPCResponse<{ encryptionAvailable: boolean }>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.SECURE_STORAGE_STATUS);
    },
  },

  settings: {
    get: (): Promise<IPCResponse<AppSettings>> => {
      return ipcRenderer.invoke(IPC_CHANNELS.SETTINGS_GET);
    },
    set: (settings: Partial<AppSettings>): Promise<IPCResponse> => {
      return ipcRenderer.invoke(IPC_CHANNELS.SETTINGS_SET, settings);
    },
  },

  app: {
    quit: (): Promise<void> => {
      return ipcRenderer.invoke('app:quit');
    },
    minimize: (): Promise<void> => {
      return ipcRenderer.invoke('app:minimize');
    },
    maximize: (): Promise<void> => {
      return ipcRenderer.invoke('app:maximize');
    },
  },

  window: {
    getBounds: (): Promise<{ x: number; y: number; width: number; height: number } | null> => {
      return ipcRenderer.invoke('window:getBounds');
    },
    resize: (width: number, height: number): Promise<void> => {
      return ipcRenderer.invoke('window:resize', width, height);
    },
  },
};

contextBridge.exposeInMainWorld('electronAPI', api);

export type ElectronAPI = typeof api;
